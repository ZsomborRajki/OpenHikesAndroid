package app.openhikes.core.data

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.OpenHikesLocalDatabase
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikePhotoSearchPlan
import app.openhikes.core.model.LibraryPhotoMatch
import app.openhikes.core.model.PhotoLibraryAsset
import app.openhikes.core.model.PhotoMatchEvidence
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.testing.TestClock
import app.openhikes.core.testing.TestFixtures
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HikePhotoImportStoreTest {
    private lateinit var portableDb: OpenHikesDatabase
    private lateinit var localDb: OpenHikesLocalDatabase
    private lateinit var photosDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        portableDb = Room.inMemoryDatabaseBuilder(context, OpenHikesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        localDb = Room.inMemoryDatabaseBuilder(context, OpenHikesLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        portableDb.close()
        localDb.close()
        if (::photosDir.isInitialized) {
            photosDir.deleteRecursively()
        }
    }

    @Test
    fun cameraCaptureMirrorsOptInDeduplicatesAndRemovesPlaceWithoutDeletingPhotos() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mirror = TestPhotoLibraryMirror()
        val repo = createRepository(dispatcher, mirror = mirror)
        val hikeId = importFixtureHike(repo)
        val spot = GeoCoordinate(47.7185, 12.8318)
        val place = TrailPoint(id = "place-1", hikeId = hikeId, coordinate = spot, name = "Lake View")
        repo.addPlace(hikeId, place).getOrThrow()

        val jpeg = SeededPhotoGenerator.generateJpeg(1)
        val captured = repo.addCameraPhoto(
            hikeId = hikeId,
            rawBytes = jpeg,
            capturedAtEpochMillis = 1_780_000_000_000L,
            coordinate = spot,
            placeId = "place-1",
            saveToSystemLibrary = true
        ).getOrThrow()

        assertThat(mirror.savedCalls).hasSize(1)
        assertThat(repo.hasPhotoFile(captured)).isTrue()
        assertThat(repo.readPhotoThumbnailBytes(captured)).isNotEmpty()
        assertThat(repo.measurePhotoStorageBytes()).isGreaterThan(0L)

        // Picking the mirrored asset again deduplicates instead of creating a second row.
        val deduped = repo.addPickedPhoto(
            hikeId = hikeId,
            bytes = jpeg,
            assetLocalIdentifier = mirror.nextAssetId,
            fallbackCoordinate = spot,
            placeId = "place-1"
        ).getOrThrow()
        assertThat(deduped.id).isEqualTo(captured.id)

        // Removing the place unfiles its photos back to the hike gallery rather than deleting them.
        repo.removePlace(hikeId, "place-1").getOrThrow()
        val updatedHike = repo.observeHike(hikeId).first()!!
        assertThat(updatedHike.places).isEmpty()
        assertThat(updatedHike.photos).hasSize(1)
        assertThat(updatedHike.photos.single().placeId).isNull()
        assertThat(repo.hasPhotoFile(updatedHike.photos.single())).isTrue()
    }

    @Test
    fun deletingPhotoAndDeletingHikeRemoveFilesAndReclaimOrphansHonorsGracePeriod() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestClock(1_780_000_600_000L)
        val repo = createRepository(dispatcher, clock = clock)
        val hikeId = importFixtureHike(repo)

        val p1 = repo.addCameraPhoto(
            hikeId = hikeId,
            rawBytes = SeededPhotoGenerator.generateJpeg(1),
            capturedAtEpochMillis = 1_780_000_100_000L,
            coordinate = GeoCoordinate(47.7185, 12.8318)
        ).getOrThrow()
        val p2 = repo.addCameraPhoto(
            hikeId = hikeId,
            rawBytes = SeededPhotoGenerator.generateJpeg(2),
            capturedAtEpochMillis = 1_780_000_200_000L,
            coordinate = GeoCoordinate(47.7190, 12.8325)
        ).getOrThrow()

        repo.removePhoto(hikeId, p1.id).getOrThrow()
        assertThat(repo.hasPhotoFile(p1)).isFalse()
        assertThat(repo.hasPhotoFile(p2)).isTrue()

        // Drop one stale orphan (10 min old) and one fresh orphan (10 sec old) on disk.
        val staleOrphan = File(photosDir, "orphan-old.jpeg").apply {
            writeBytes(SeededPhotoGenerator.generateJpeg(9))
            setLastModified(clock.now().toEpochMilli() - 600_000L)
        }
        val freshOrphan = File(photosDir, "orphan-fresh.jpeg").apply {
            writeBytes(SeededPhotoGenerator.generateJpeg(10))
            setLastModified(clock.now().toEpochMilli() - 10_000L)
        }

        val reclaimed = repo.reclaimOrphanedPhotos().getOrThrow()
        assertThat(reclaimed).isEqualTo(1)
        assertThat(staleOrphan.exists()).isFalse()
        assertThat(freshOrphan.exists()).isTrue()

        // Deleting the hike removes p2's disk file after DB delete.
        repo.deleteHike(hikeId).getOrThrow()
        assertThat(repo.hasPhotoFile(p2)).isFalse()
    }

    @Test
    fun discoveredPhotoRejectsStaleFingerprintWithoutLeavingOrphanFile() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = createRepository(dispatcher)
        val hikeId = importFixtureHike(repo)
        val hike = repo.observeHike(hikeId).first()!!
        val realFingerprint = HikePhotoSearchPlan.fingerprint(hike.route, emptyList())
        val match = LibraryPhotoMatch(
            asset = PhotoLibraryAsset("lib-1", 1_780_000_100_000L, GeoCoordinate(47.7185, 12.8318)),
            coordinate = GeoCoordinate(47.7185, 12.8318),
            evidence = PhotoMatchEvidence.TIME_AND_PLACE,
            secondsFromFix = 0.0
        )

        val staleResult = repo.addDiscoveredPhoto(
            hikeId = hikeId,
            bytes = SeededPhotoGenerator.generateJpeg(3),
            match = match,
            expectedPlanFingerprint = realFingerprint xor 0xFFL
        ).getOrThrow()
        assertThat(staleResult).isNull()
        assertThat(repo.observeHike(hikeId).first()!!.photos).isEmpty()
        assertThat(photosDir.listFiles()?.filter { it.isFile }).isEmpty()

        val validResult = repo.addDiscoveredPhoto(
            hikeId = hikeId,
            bytes = SeededPhotoGenerator.generateJpeg(3),
            match = match,
            expectedPlanFingerprint = realFingerprint
        ).getOrThrow()
        assertThat(validResult).isNotNull()
        assertThat(repo.observeHike(hikeId).first()!!.photos).hasSize(1)
    }

    @Test
    fun prepareSharePhotoFileCreatesUniqueImmutableFilesWithReadableDisplayName() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = createRepository(dispatcher)
        val hikeIdA = importFixtureHike(repo)
        val hikeIdB = importFixtureHike(repo)
        val jpegA = createSizedJpeg(width = 32, height = 24)
        val jpegB = createSizedJpeg(width = 24, height = 32)

        val photoA = repo.addCameraPhoto(
            hikeId = hikeIdA,
            rawBytes = jpegA,
            capturedAtEpochMillis = 1_780_000_100_000L,
            coordinate = GeoCoordinate(47.7185, 12.8318)
        ).getOrThrow()
        val photoB = repo.addCameraPhoto(
            hikeId = hikeIdB,
            rawBytes = jpegB,
            capturedAtEpochMillis = 1_780_000_200_000L,
            coordinate = GeoCoordinate(47.7190, 12.8325)
        ).getOrThrow()

        val preparedA = repo.prepareSharePhotoFile("Thumsee Loop", 1, photoA).getOrThrow()
        val snapshotA = preparedA.file.readBytes()
        val preparedB = repo.prepareSharePhotoFile("Thumsee Loop", 1, photoB).getOrThrow()

        assertThat(preparedA.file.absolutePath).isNotEqualTo(preparedB.file.absolutePath)
        assertThat(preparedA.displayName).isEqualTo("Thumsee Loop-1.jpeg")
        assertThat(preparedB.displayName).isEqualTo("Thumsee Loop-1.jpeg")
        assertThat(preparedA.file.readBytes()).isEqualTo(snapshotA)
        assertThat(preparedB.file.readBytes()).isNotEqualTo(snapshotA)
    }

    @Test
    fun cameraCapturePrefersExifTimestampBeforeFallbackMillis() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = createRepository(dispatcher)
        val hikeId = importFixtureHike(repo)
        val exifMillis = 1_780_000_060_000L
        val callbackFallbackMillis = 1_780_000_360_000L

        val stampedFile = File.createTempFile("exif-camera-", ".jpg")
        try {
            stampedFile.writeBytes(SeededPhotoGenerator.generateJpeg(4))
            assertThat(
                PhotoMetadataCodec.stampCaptureMetadata(
                    file = stampedFile,
                    capturedAtEpochMillis = exifMillis,
                    coordinate = GeoCoordinate(47.7185, 12.8318),
                    zone = ZoneOffset.UTC
                )
            ).isTrue()
            val stampedBytes = stampedFile.readBytes()

            val fromExif = repo.addCameraPhoto(
                hikeId = hikeId,
                rawBytes = stampedBytes,
                capturedAtEpochMillis = callbackFallbackMillis,
                coordinate = GeoCoordinate(47.7185, 12.8318)
            ).getOrThrow()
            assertThat(fromExif.capturedAtEpochMillis).isEqualTo(exifMillis)
        } finally {
            stampedFile.delete()
        }

        val withoutExif = repo.addCameraPhoto(
            hikeId = hikeId,
            rawBytes = SeededPhotoGenerator.generateJpeg(5),
            capturedAtEpochMillis = callbackFallbackMillis,
            coordinate = GeoCoordinate(47.7190, 12.8325)
        ).getOrThrow()
        assertThat(withoutExif.capturedAtEpochMillis).isEqualTo(callbackFallbackMillis)
    }

    @Test
    fun exportHikeArchiveToUriStreamsValidZipToDestination() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fileIo = TestPhotoFileIo()
        val repo = createRepository(dispatcher, photoFileIo = fileIo)
        val hikeId = importFixtureHike(repo)
        val jpeg = SeededPhotoGenerator.generateJpeg(6)
        repo.addCameraPhoto(
            hikeId = hikeId,
            rawBytes = jpeg,
            capturedAtEpochMillis = 1_780_000_120_000L,
            coordinate = GeoCoordinate(47.7185, 12.8318)
        ).getOrThrow()

        repo.exportHikeArchiveToUri(hikeId, "content://exports/thumsee.zip").getOrThrow()

        val zipBytes = requireNotNull(fileIo.writtenArchiveByUri["content://exports/thumsee.zip"])
        val entryNames = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryNames.add(entry.name)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        assertThat(entryNames.any { it.endsWith(".gpx") }).isTrue()
        assertThat(entryNames.any { it.endsWith("/Photos/photo-1.jpeg") }).isTrue()
    }

    @Test
    fun uniqueAssetIndexAllowsMultipleNullCameraPhotosAndRejectsDuplicateAssetRows() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = createRepository(dispatcher)
        val hikeId = importFixtureHike(repo)

        val cam1 = repo.addCameraPhoto(
            hikeId = hikeId,
            rawBytes = SeededPhotoGenerator.generateJpeg(1),
            capturedAtEpochMillis = 1_780_000_100_000L,
            coordinate = GeoCoordinate(47.7185, 12.8318)
        ).getOrThrow()
        val cam2 = repo.addCameraPhoto(
            hikeId = hikeId,
            rawBytes = SeededPhotoGenerator.generateJpeg(2),
            capturedAtEpochMillis = 1_780_000_200_000L,
            coordinate = GeoCoordinate(47.7190, 12.8325)
        ).getOrThrow()
        assertThat(cam1.id).isNotEqualTo(cam2.id)

        val picked = repo.addPickedPhoto(
            hikeId = hikeId,
            bytes = SeededPhotoGenerator.generateJpeg(3),
            assetLocalIdentifier = "asset-unique-42",
            fallbackCoordinate = GeoCoordinate(47.7188, 12.8320)
        ).getOrThrow()
        val conflictingRowId = portableDb.hikePhotoDao().insert(
            picked.copy(id = "conflicting-duplicate-id").toEntity()
        )
        assertThat(conflictingRowId).isEqualTo(-1L)
        assertThat(portableDb.hikePhotoDao().getByHikeId(hikeId.value)).hasSize(3)
    }

    private fun createSizedJpeg(width: Int, height: Int): ByteArray {
        val bitmap = createBitmap(width, height)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private suspend fun importFixtureHike(repo: OfflineFirstHikeRepository): HikeId {
        val result = repo.importGpxBytes(
            bytes = TestFixtures.thumseeLoopFastGpxBytes(),
            fileName = "${TestFixtures.THUMSEE_LOOP_FAST_NAME}.gpx"
        ) as GpxImportResult.Success
        return result.importedHikes.first().id
    }

    private fun createRepository(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        mirror: TestPhotoLibraryMirror = TestPhotoLibraryMirror(),
        photoFileIo: TestPhotoFileIo = TestPhotoFileIo(),
        clock: TestClock = TestClock(1_780_000_000_000L)
    ): OfflineFirstHikeRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (dir, photoStore) = createTestPhotoStore(dispatcher, context)
        photosDir = dir
        return OfflineFirstHikeRepository(
            context = context,
            database = portableDb,
            hikeDao = portableDb.hikeDao(),
            trailPointDao = portableDb.trailPointDao(),
            hikePhotoDao = portableDb.hikePhotoDao(),
            hikeWalkDao = portableDb.hikeWalkDao(),
            hikeLocalStateDao = localDb.hikeLocalStateDao(),
            photoStore = photoStore,
            photoLibraryMirror = mirror,
            photoFileIo = photoFileIo,
            gpxFileSource = object : GpxFileSource {
                override suspend fun read(uriString: String): GpxFilePayload? = null
            },
            gpxFileSink = object : GpxFileSink {
                override suspend fun write(uriString: String, xml: String): Result<Unit> = Result.success(Unit)
            },
            bundledGpxFixtureSource = object : BundledGpxFixtureSource {
                override suspend fun loadFixture(fixtureName: String): Pair<String, ByteArray>? = null
            },
            launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production),
            clock = clock,
            ioDispatcher = dispatcher,
            defaultDispatcher = dispatcher
        )
    }
}

package app.openhikes.core.testing.android

import app.openhikes.core.data.PhotoLibraryAccess
import app.openhikes.core.data.PhotoLibrarySource
import app.openhikes.core.data.SeededPhotoGenerator
import app.openhikes.core.model.PhotoLibraryAsset

/**
 * Deterministic in-memory [PhotoLibrarySource] fake for Photo Discovery ViewModel and UI tests.
 */
class FakePhotoLibrarySource(
    var access: PhotoLibraryAccess = PhotoLibraryAccess.GRANTED,
    initialAssets: List<PhotoLibraryAsset> = emptyList()
) : PhotoLibrarySource {
    var assets: List<PhotoLibraryAsset> = initialAssets
    val bytesByAssetId: MutableMap<String, ByteArray> = HashMap()
    val failingReadIds: MutableSet<String> = HashSet()
    var accessAfterQueryOverride: PhotoLibraryAccess? = null
    var queryCount: Int = 0
        private set

    override suspend fun currentAccess(): PhotoLibraryAccess = access

    override suspend fun queryAssets(window: LongRange): List<PhotoLibraryAsset> {
        queryCount += 1
        val matching = assets.filter { it.createdAtEpochMillis in window }
        accessAfterQueryOverride?.let { access = it }
        return matching
    }

    override suspend fun readImageBytes(localIdentifier: String): ByteArray? = if (localIdentifier in failingReadIds) {
        null
    } else {
        bytesByAssetId.getOrPut(localIdentifier) {
            runCatching { SeededPhotoGenerator.generateJpeg(localIdentifier.hashCode()) }
                .getOrElse { DEFAULT_TEST_JPEG_BYTES.copyOf() }
        }
    }

    override suspend fun readThumbnailBytes(localIdentifier: String, maxDimensionPx: Int): ByteArray? =
        readImageBytes(localIdentifier)

    private companion object {
        val DEFAULT_TEST_JPEG_BYTES = byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0xFF.toByte(),
            0xE0.toByte(),
            0x00,
            0x10,
            0x4A,
            0x46,
            0x49,
            0x46,
            0x00,
            0x01,
            0xFF.toByte(),
            0xD9.toByte()
        )
    }
}

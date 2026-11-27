package app.openhikes.core.common.offline

import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.OfflineBounds
import app.openhikes.core.model.OfflineCountry
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.model.PendingCountryDownload
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineCountryStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun catalogParserDropsUnknownFileRoles() {
        val rawCatalog = """
            {
              "version": 1,
              "countries": [
                {
                  "id": "AT",
                  "names": {},
                  "build": "20261117",
                  "schema": 4,
                  "bounds": { "west": 9.5, "south": 46.4, "east": 17.0, "north": 49.0 },
                  "outline": [],
                  "files": [
                    {
                      "role": "basemap",
                      "path": "countries/AT-20261117.pmtiles",
                      "bytes": 100,
                      "sha256": "${"a".repeat(64)}",
                      "minimumZoom": 0,
                      "maximumZoom": 14
                    },
                    {
                      "role": "future-contours",
                      "path": "countries/AT-contours-20261117.pmtiles",
                      "bytes": 50,
                      "sha256": "${"b".repeat(64)}",
                      "minimumZoom": 0,
                      "maximumZoom": 14
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val manifest = OfflineCountryManifestParser.parseCatalog(rawCatalog)
        assertThat(manifest.readableCountries).hasSize(1)
        assertThat(manifest.readableCountries.single().files.map { it.role })
            .containsExactly(OfflineFileRole.BASEMAP)
    }

    @Test
    fun strictIndexParserRefusesUnknownFileRoles() {
        val store = OfflineCountryStore(File(tempFolder.root, "OfflineCountries"))
        store.prepare()
        store.indexFile.writeText(
            """
            [
              {
                "country": {
                  "id": "AT",
                  "names": {},
                  "build": "20261117",
                  "schema": 4,
                  "bounds": { "west": 9.5, "south": 46.4, "east": 17.0, "north": 49.0 },
                  "outline": [],
                  "files": [
                    {
                      "role": "future-contours",
                      "path": "countries/AT-20261117.pmtiles",
                      "bytes": 100,
                      "sha256": "${"a".repeat(64)}",
                      "minimumZoom": 0,
                      "maximumZoom": 14
                    }
                  ]
                },
                "installedAtEpochMillis": 1,
                "hasDetail": false
              }
            ]
            """.trimIndent()
        )
        assertThat(store.readIndex()).isEqualTo(OfflineStoreList.Unreadable)
    }

    @Test
    fun verifiesSha256InstallsAtomicallyRollsBackOnFailureAndSweepsOrphans() {
        val store = OfflineCountryStore(File(tempFolder.root, "OfflineCountries"))
        store.prepare()

        val payload = "verified-pmtiles-bytes".encodeToByteArray()
        val digest = OfflineCountryDigest.sha256Hex(payload)
        val file1 = OfflineCountryFile(
            role = OfflineFileRole.BASEMAP,
            path = "countries/AT-20261117.pmtiles",
            byteCount = payload.size.toLong(),
            sha256 = digest,
            minimumZoom = 0,
            maximumZoom = 14
        )
        val file2 = OfflineCountryFile(
            role = OfflineFileRole.EXTRAS,
            path = "countries/AT-extras-20261117.pmtiles",
            byteCount = payload.size.toLong(),
            sha256 = digest,
            minimumZoom = 0,
            maximumZoom = 14
        )

        val staged1 = store.stagedFileFor(file1).apply { writeBytes(payload) }
        assertThat(OfflineCountryDigest.isWhole(file1, staged1)).isTrue()

        // Missing file2 causes install(file1, file2) to fail and roll back file1 to staging.
        assertThrows(Exception::class.java) { store.install(listOf(file1, file2)) }
        assertThat(store.stagedFileFor(file1).isFile).isTrue()
        assertThat(store.fileFor(file1).exists()).isFalse()

        // Once both are staged, install moves both into place atomically.
        store.stagedFileFor(file2).writeBytes(payload)
        store.install(listOf(file1, file2))
        assertThat(store.fileFor(file1).isFile).isTrue()
        assertThat(store.fileFor(file2).isFile).isTrue()

        val country = OfflineCountry(
            id = "AT",
            build = "20261117",
            schema = 4,
            bounds = OfflineBounds(9.5, 46.4, 17.0, 49.0),
            files = listOf(file1, file2)
        )
        val record = InstalledCountryRecord(country = country, installedAtEpochMillis = 42L, hasDetail = false)
        store.writeInstalled(listOf(record))
        store.writePending(emptyList<PendingCountryDownload>())

        val orphanRoot = File(store.directory, "ZZ-20261117.pmtiles").apply { writeText("orphan") }
        val orphanStaged = File(store.stagingDirectory, "ZZ-20261117.pmtiles").apply { writeText("orphan") }
        store.sweep(keeping = listOf(record), pending = emptyList())

        assertThat(orphanRoot.exists()).isFalse()
        assertThat(orphanStaged.exists()).isFalse()
        assertThat(store.fileFor(file1).isFile).isTrue()
        assertThat(store.readIndex()).isEqualTo(OfflineStoreList.Holding(listOf(record)))
    }

    @Test
    fun legacyIndexWithoutHasElevationInfersPresenceFromListedElevationFileAndPreservesExplicitFalse() {
        val store = OfflineCountryStore(File(tempFolder.root, "OfflineCountries"))
        store.prepare()
        store.indexFile.writeText(legacyIndexWithoutHasElevationJson())

        val holding = store.readIndex() as OfflineStoreList.Holding
        val byId = holding.items.associateBy { it.country.id }
        assertThat(byId.getValue("AT").hasElevation).isFalse()
        assertThat(byId.getValue("SI").hasElevation).isTrue()

        // Writing back explicit hasElevation = false keeps it false on subsequent decode.
        val strippedSi = byId.getValue("SI").copy(hasElevation = false)
        store.writeInstalled(listOf(byId.getValue("AT"), strippedSi))
        val reread = (store.readIndex() as OfflineStoreList.Holding).items.associateBy { it.country.id }
        assertThat(reread.getValue("SI").hasElevation).isFalse()
        assertThat(reread.getValue("SI").files.map { it.role }).containsExactly(OfflineFileRole.BASEMAP)
    }

    private fun legacyIndexWithoutHasElevationJson(): String = """
        [
          {
            "country": {
              "id": "AT",
              "names": {},
              "build": "20261117",
              "schema": 4,
              "bounds": { "west": 9.5, "south": 46.4, "east": 17.0, "north": 49.0 },
              "outline": [],
              "files": [
                {
                  "role": "basemap",
                  "path": "countries/AT-20261117.pmtiles",
                  "bytes": 100,
                  "sha256": "${"a".repeat(64)}",
                  "minimumZoom": 0,
                  "maximumZoom": 14
                }
              ]
            },
            "installedAtEpochMillis": 10,
            "hasDetail": false
          },
          {
            "country": {
              "id": "SI",
              "names": {},
              "build": "20261117",
              "schema": 4,
              "bounds": { "west": 13.6, "south": 45.45, "east": 16.6, "north": 46.85 },
              "outline": [],
              "files": [
                {
                  "role": "basemap",
                  "path": "countries/SI-20261117.pmtiles",
                  "bytes": 100,
                  "sha256": "${"b".repeat(64)}",
                  "minimumZoom": 0,
                  "maximumZoom": 14
                },
                {
                  "role": "elevation",
                  "path": "countries/SI-elevation-20261117.pmtiles",
                  "bytes": 50,
                  "sha256": "${"c".repeat(64)}",
                  "minimumZoom": 12,
                  "maximumZoom": 12
                }
              ]
            },
            "installedAtEpochMillis": 20,
            "hasDetail": false
          }
        ]
    """.trimIndent()
}

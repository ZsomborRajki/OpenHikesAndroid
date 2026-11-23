package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class OfflineCountryDirectoryTest {
    private val at = makeCountry("AT", bytes = 1_000L, west = 9.5, south = 46.4, east = 17.0, north = 49.0)
    private val de = makeCountry("DE", bytes = 4_000L, west = 6.0, south = 47.2, east = 15.0, north = 54.9)
    private val fr = makeCountry("FR", bytes = 6_000L, west = -4.7, south = 41.4, east = 9.5, north = 51.1)
    private val no = makeCountry("NO", bytes = 2_000L, west = 4.9, south = 58.0, east = 31.0, north = 71.1)
    private val usCo = makeCountry(
        id = "US-CO",
        group = "US",
        names = mapOf("en" to "Colorado"),
        bytes = 1_200L,
        west = -109.0,
        south = 37.0,
        east = -102.0,
        north = 41.0
    )
    private val usWa = makeCountry(
        id = "US-WA",
        group = "US",
        names = mapOf("en" to "Washington"),
        bytes = 1_400L,
        west = -124.7,
        south = 45.6,
        east = -117.0,
        north = 49.0
    )

    @Test
    fun buildsAlphabeticalSectionsWithSplitCountryGroupAndPromotedSections() {
        val state = OfflineCountriesState(
            offered = listOf(at, de, fr, no, usCo, usWa),
            installed = mapOf(
                "DE" to InstalledCountryRecord(de, installedAtEpochMillis = 100L, hasDetail = true)
            ),
            downloads = mapOf(
                "FR" to OfflineCountryDownload(
                    country = fr,
                    roles = listOf(OfflineFileRole.BASEMAP),
                    phase = OfflineDownloadPhase.DOWNLOADING
                )
            )
        )

        val directory = OfflineCountryDirectoryBuilder.build(
            state = state,
            sort = OfflineCountrySort.NAME,
            locale = Locale.US
        )

        assertThat(directory.unfinished.map { it.country.id }).containsExactly("FR")
        assertThat(directory.downloaded.map { it.country.id }).containsExactly("DE")

        val letterTitles = directory.sections.map { it.title }
        assertThat(letterTitles).containsAtLeast("A", "N", "U").inOrder()
        val uSection = directory.sections.first { it.title == "U" }
        val group = uSection.items.single() as OfflineDirectoryItem.GroupItem
        assertThat(group.groupId).isEqualTo("US")
        assertThat(group.regions.map { it.country.id }).containsExactly("US-CO", "US-WA").inOrder()
    }

    @Test
    fun ordersNearestFromUserLocationAndLargestByBytes() {
        val state = OfflineCountriesState(offered = listOf(at, de, fr, no, usCo, usWa))
        val thumsee = GeoCoordinate(latitude = 47.718, longitude = 12.831)

        val nearest = OfflineCountryDirectoryBuilder.build(
            state = state,
            sort = OfflineCountrySort.NEAREST,
            userLocation = thumsee,
            locale = Locale.US
        )
        assertThat(nearest.sections.first().kind).isEqualTo(OfflineDirectorySectionKind.NEARBY)
        val nearbyIds = nearest.sections.first().items
            .filterIsInstance<OfflineDirectoryItem.CountryItem>()
            .map { it.entry.country.id }
        assertThat(nearbyIds).containsAtLeast("DE", "AT")

        val largest = OfflineCountryDirectoryBuilder.build(
            state = state,
            sort = OfflineCountrySort.LARGEST,
            locale = Locale.US
        )
        val orderedIds = largest.sections.single().items
            .filterIsInstance<OfflineDirectoryItem.CountryItem>()
            .map { it.entry.country.id }
        assertThat(orderedIds.take(2)).containsExactly("FR", "DE").inOrder()
    }

    @Test
    fun searchRanksPrefixMatchesAheadOfContainsAndMatchesParentCountryName() {
        val state = OfflineCountriesState(offered = listOf(at, de, fr, no, usCo, usWa))

        val waMatches = OfflineCountryDirectoryBuilder.search(state, "wa", Locale.US)
        assertThat(waMatches.map { it.country.id }).containsExactly("US-WA", "NO").inOrder()

        val unitedMatches = OfflineCountryDirectoryBuilder.search(state, "united", Locale.US)
        assertThat(unitedMatches.map { it.country.id }).containsExactly("US-CO", "US-WA").inOrder()

        assertThat(OfflineCountryDirectoryBuilder.search(state, "Atlantis", Locale.US)).isEmpty()
    }

    private fun makeCountry(
        id: String,
        bytes: Long,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        group: String? = null,
        names: Map<String, String> = emptyMap()
    ): OfflineCountry = OfflineCountry(
        id = id,
        group = group,
        names = names,
        build = "20261117",
        schema = OfflineCountryManifest.READABLE_SCHEMA,
        bounds = OfflineBounds(west, south, east, north),
        outline = listOf(
            listOf(
                listOf(west, south),
                listOf(east, south),
                listOf(east, north),
                listOf(west, north),
                listOf(west, south)
            )
        ),
        files = listOf(
            OfflineCountryFile(
                role = OfflineFileRole.BASEMAP,
                path = "countries/$id-20261117.pmtiles",
                byteCount = bytes,
                sha256 = "0".repeat(64),
                minimumZoom = 0,
                maximumZoom = 14
            )
        )
    )
}

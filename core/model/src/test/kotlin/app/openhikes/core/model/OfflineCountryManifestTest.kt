package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class OfflineCountryManifestTest {
    @Test
    fun validatesAreaCodesAndSafeFileNames() {
        assertThat(OfflineCountryManifest.isAreaCode("AT")).isTrue()
        assertThat(OfflineCountryManifest.isAreaCode("US-CO")).isTrue()
        assertThat(OfflineCountryManifest.isAreaCode("RU-MOW")).isTrue()
        assertThat(OfflineCountryManifest.isAreaCode("at")).isFalse()
        assertThat(OfflineCountryManifest.isAreaCode("USA")).isFalse()
        assertThat(OfflineCountryManifest.isAreaCode("US-1234")).isFalse()
        assertThat(OfflineCountryManifest.isAreaCode("US-")).isFalse()

        assertThat(sampleFile("countries/AT-20261117.pmtiles").hasSafeName).isTrue()
        assertThat(sampleFile("countries/AT-extras-20261117.pmtiles").hasSafeName).isTrue()
        assertThat(sampleFile("countries/AT-detail-20261117.pmtiles").hasSafeName).isTrue()
        assertThat(sampleFile("countries/US-CO-detail-extras-20261117.pmtiles").hasSafeName).isTrue()
        assertThat(sampleFile("countries/AT-extras-detail-20261117.pmtiles").hasSafeName).isFalse()
        assertThat(sampleFile("countries/AT-2026.pmtiles").hasSafeName).isFalse()
        assertThat(sampleFile("../AT-20261117.pmtiles").fileName).isEqualTo("AT-20261117.pmtiles")
        assertThat(sampleFile("countries/../../evil.pmtiles").hasSafeName).isFalse()
    }

    @Test
    fun readableCountriesFiltersIncompatibleBuildsAndDeduplicatesKeepingNewestBuild() {
        val olderAt = sampleCountry(id = "AT", build = "20261110")
        val newerAt = sampleCountry(id = "AT", build = "20261117")
        val wrongSchema = sampleCountry(id = "DE", build = "20261117", schema = 99)
        val futureExtras = sampleCountry(id = "CH", build = "20261117", extrasGeneration = 99)
        val mismatchedPrefix = sampleCountry(
            id = "SI",
            build = "20261117",
            files = listOf(sampleFile("countries/AT-20261117.pmtiles"))
        )
        val manifest = OfflineCountryManifest(
            version = 1,
            countries = listOf(olderAt, wrongSchema, futureExtras, mismatchedPrefix, newerAt)
        )

        val readable = manifest.readableCountries
        assertThat(readable).containsExactly(newerAt)
    }

    @Test
    fun pointInPolygonAndLocalizedNamesWorkForCountriesAndRegions() {
        val colorado = sampleCountry(
            id = "US-CO",
            build = "20261117",
            group = "US",
            names = mapOf("en" to "Colorado", "pl" to "Kolorado"),
            bounds = OfflineBounds(west = -109.05, south = 37.0, east = -102.04, north = 41.0),
            outline = listOf(
                listOf(
                    listOf(-109.05, 37.0),
                    listOf(-102.04, 37.0),
                    listOf(-102.04, 41.0),
                    listOf(-109.05, 41.0),
                    listOf(-109.05, 37.0)
                )
            )
        )

        assertThat(colorado.contains(longitude = -105.0, latitude = 39.5)).isTrue()
        assertThat(colorado.contains(longitude = -112.0, latitude = 39.5)).isFalse()
        assertThat(colorado.localizedName(Locale.US)).isEqualTo("Colorado")
        assertThat(colorado.localizedName(Locale.forLanguageTag("pl"))).isEqualTo("Kolorado")
        assertThat(colorado.fullName(Locale.US)).isEqualTo("Colorado, United States")
    }

    private fun sampleFile(path: String, role: OfflineFileRole = OfflineFileRole.BASEMAP): OfflineCountryFile =
        OfflineCountryFile(
            role = role,
            path = path,
            byteCount = 1_000L,
            sha256 = "0".repeat(64),
            minimumZoom = 0,
            maximumZoom = 14
        )

    private fun sampleCountry(
        id: String,
        build: String,
        group: String? = null,
        names: Map<String, String> = emptyMap(),
        schema: Int = OfflineCountryManifest.READABLE_SCHEMA,
        extrasGeneration: Int? = 2,
        bounds: OfflineBounds = OfflineBounds(0.0, 0.0, 10.0, 10.0),
        outline: List<List<List<Double>>> = emptyList(),
        files: List<OfflineCountryFile> = listOf(sampleFile("countries/$id-$build.pmtiles"))
    ): OfflineCountry = OfflineCountry(
        id = id,
        group = group,
        names = names,
        build = build,
        schema = schema,
        extrasGeneration = extrasGeneration,
        bounds = bounds,
        outline = outline,
        files = files
    )
}

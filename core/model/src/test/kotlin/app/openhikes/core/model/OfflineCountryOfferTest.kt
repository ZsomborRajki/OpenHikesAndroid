package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OfflineCountryOfferTest {
    @Test
    fun roleOptionalityAndExtraMappingMatchEveryRole() {
        assertThat(OfflineCountryExtra.fromRoleOrNull(OfflineFileRole.BASEMAP)).isNull()
        assertThat(OfflineCountryExtra.fromRoleOrNull(OfflineFileRole.EXTRAS)).isNull()
        assertThat(OfflineCountryExtra.fromRoleOrNull(OfflineFileRole.DETAIL)).isEqualTo(OfflineCountryExtra.DETAIL)
        assertThat(OfflineCountryExtra.fromRoleOrNull(OfflineFileRole.DETAIL_EXTRAS))
            .isEqualTo(OfflineCountryExtra.DETAIL)
        assertThat(OfflineCountryExtra.fromRoleOrNull(OfflineFileRole.ELEVATION))
            .isEqualTo(OfflineCountryExtra.ELEVATION)

        for (role in OfflineFileRole.entries) {
            assertThat(role.isOptional).isEqualTo(OfflineCountryExtra.fromRoleOrNull(role) != null)
        }
    }

    @Test
    fun countryFilesAndByteCountsRespectAllSwitchCombinations() {
        val country = sampleCountry(
            basemapBytes = 100L,
            extrasBytes = 20L,
            detailBytes = 110L,
            detailExtrasBytes = 15L,
            elevationBytes = 40L
        )

        assertThat(country.byteCount(withDetail = true, withElevation = true)).isEqualTo(285L)
        assertThat(country.byteCount(withDetail = true, withElevation = false)).isEqualTo(245L)
        assertThat(country.byteCount(withDetail = false, withElevation = true)).isEqualTo(160L)
        assertThat(country.byteCount(withDetail = false, withElevation = false)).isEqualTo(120L)
        assertThat(country.files(withDetail = false, withElevation = false).map { it.role })
            .containsExactly(OfflineFileRole.BASEMAP, OfflineFileRole.EXTRAS)
            .inOrder()
    }

    @Test
    fun additionAndRemovalOffersCoverMatchedOppositeAndCombinedStates() {
        val listed = sampleCountry(
            basemapBytes = 100L,
            detailBytes = 110L,
            detailExtrasBytes = 15L,
            elevationBytes = 40L
        )

        val notInstalled = OfflineCountriesState(offered = listOf(listed))
        assertThat(notInstalled.addition("SI")).isNull()
        assertThat(notInstalled.removal("SI")).isNull()

        val matched = installedState(listed, hasDetail = true, hasElevation = true)
        assertThat(matched.addition("SI")).isNull()
        assertThat(matched.removal("SI")).isNull()

        val opposite = installedState(listed, hasDetail = true, hasElevation = false, includesDetail = false)
        assertThat(opposite.addition("SI")?.extras).containsExactly(OfflineCountryExtra.ELEVATION)
        assertThat(opposite.addition("SI")?.byteCount).isEqualTo(40L)
        assertThat(opposite.removal("SI")?.extras).containsExactly(OfflineCountryExtra.DETAIL)
        assertThat(opposite.removal("SI")?.byteCount).isEqualTo(125L)

        val addBoth = requireNotNull(installedState(listed, hasDetail = false, hasElevation = false).addition("SI"))
        assertThat(addBoth.extras).containsExactly(OfflineCountryExtra.DETAIL, OfflineCountryExtra.ELEVATION)
        assertThat(addBoth.byteCount).isEqualTo(165L)

        val removeBoth = requireNotNull(matched.copy(includesDetail = false, includesElevation = false).removal("SI"))
        assertThat(removeBoth.extras).containsExactly(OfflineCountryExtra.DETAIL, OfflineCountryExtra.ELEVATION)
        assertThat(removeBoth.byteCount).isEqualTo(165L)
    }

    @Test
    fun unsettledCountriesSuppressAdditionAndRemovalOffers() {
        val listed = sampleCountry(basemapBytes = 100L, detailBytes = 110L, elevationBytes = 40L)
        val opposite = installedState(listed, hasDetail = true, hasElevation = false, includesDetail = false)

        val recovering = opposite.copy(recovering = setOf("SI"))
        assertThat(recovering.addition("SI")).isNull()
        assertThat(recovering.removal("SI")).isNull()

        val downloading = opposite.copy(
            downloads = mapOf(
                "SI" to OfflineCountryDownload(
                    country = listed,
                    roles = listOf(OfflineFileRole.ELEVATION),
                    phase = OfflineDownloadPhase.DOWNLOADING
                )
            )
        )
        assertThat(downloading.addition("SI")).isNull()
        assertThat(downloading.removal("SI")).isNull()
    }

    @Test
    fun additionRespectsCatalogAvailabilityRepairStateAndCarriesGainedMandatoryFiles() {
        val baseOnly = sampleCountry(basemapBytes = 100L)
        val withExtrasAndElevation = sampleCountry(basemapBytes = 100L, extrasBytes = 25L, elevationBytes = 40L)
        val newerBuild =
            sampleCountry(build = "20261201", basemapBytes = 100L, detailBytes = 110L, elevationBytes = 40L)

        val gone = installedState(
            country = withExtrasAndElevation,
            offered = emptyList(),
            hasDetail = false,
            hasElevation = true,
            includesElevation = false
        )
        assertThat(gone.addition("SI")).isNull()
        assertThat(gone.removal("SI")?.extras).containsExactly(OfflineCountryExtra.ELEVATION)

        val updateAvailable = gone.copy(offered = listOf(newerBuild))
        assertThat(updateAvailable.addition("SI")).isNull()
        assertThat(updateAvailable.removal("SI")?.extras).containsExactly(OfflineCountryExtra.ELEVATION)

        val damaged = gone.copy(
            offered = listOf(withExtrasAndElevation),
            unopenable = mapOf("SI" to setOf(OfflineFileRole.ELEVATION))
        )
        assertThat(damaged.addition("SI")).isNull()
        assertThat(damaged.removal("SI")?.extras).containsExactly(OfflineCountryExtra.ELEVATION)

        val gained = installedState(
            country = baseOnly,
            offered = listOf(withExtrasAndElevation),
            hasDetail = false,
            hasElevation = false
        )
        val offer = requireNotNull(gained.addition("SI"))
        assertThat(offer.extras).containsExactly(OfflineCountryExtra.ELEVATION)
        assertThat(offer.files.map { it.role }).containsExactly(OfflineFileRole.EXTRAS, OfflineFileRole.ELEVATION)
        assertThat(offer.byteCount).isEqualTo(65L)
    }

    private fun installedState(
        country: OfflineCountry,
        offered: List<OfflineCountry> = listOf(country),
        hasDetail: Boolean,
        hasElevation: Boolean,
        includesDetail: Boolean = true,
        includesElevation: Boolean = true
    ): OfflineCountriesState = OfflineCountriesState(
        offered = offered,
        installed = mapOf(
            country.id to InstalledCountryRecord(
                country = country,
                installedAtEpochMillis = 1L,
                hasDetail = hasDetail,
                hasElevation = hasElevation
            )
        ),
        includesDetail = includesDetail,
        includesElevation = includesElevation
    )

    @Test
    fun filesToFetchAndFilesToRetryRespectRepairAndSwitchChanges() {
        val listed = sampleCountry(basemapBytes = 100L, detailBytes = 110L, elevationBytes = 40L)
        val installedRecord = InstalledCountryRecord(
            country = listed,
            installedAtEpochMillis = 1L,
            hasDetail = false,
            hasElevation = true
        )

        // Repair ignores switches and brings only the unopenable file held by the record.
        val repairState = OfflineCountriesState(
            offered = listOf(listed),
            installed = mapOf("SI" to installedRecord),
            unopenable = mapOf("SI" to setOf(OfflineFileRole.ELEVATION)),
            includesDetail = true,
            includesElevation = false
        )
        assertThat(repairState.filesToFetch(listed).map { it.role }).containsExactly(OfflineFileRole.ELEVATION)

        // Retrying a failed addition drops roles whose switch was turned off since failure.
        val failedAddition = OfflineCountryDownload(
            country = listed,
            roles = listOf(OfflineFileRole.DETAIL, OfflineFileRole.ELEVATION),
            phase = OfflineDownloadPhase.FAILED,
            failure = OfflineDownloadFailure.CONNECTION
        )
        val retryState = OfflineCountriesState(
            offered = listOf(listed),
            installed = mapOf(
                "SI" to
                    InstalledCountryRecord(listed, installedAtEpochMillis = 1L, hasDetail = false, hasElevation = false)
            ),
            includesDetail = false,
            includesElevation = true
        )
        assertThat(retryState.filesToRetry(failedAddition).map { it.role }).containsExactly(OfflineFileRole.ELEVATION)
        assertThat(retryState.copy(includesElevation = false).filesToRetry(failedAddition)).isEmpty()
    }

    private fun sampleCountry(
        id: String = "SI",
        build: String = "20261117",
        basemapBytes: Long,
        extrasBytes: Long? = null,
        detailBytes: Long? = null,
        detailExtrasBytes: Long? = null,
        elevationBytes: Long? = null
    ): OfflineCountry {
        val files = buildList {
            add(file(OfflineFileRole.BASEMAP, "countries/$id-$build.pmtiles", basemapBytes, 0, 14))
            if (extrasBytes != null) {
                add(file(OfflineFileRole.EXTRAS, "countries/$id-extras-$build.pmtiles", extrasBytes, 0, 14))
            }
            if (detailBytes != null) {
                add(file(OfflineFileRole.DETAIL, "countries/$id-detail-$build.pmtiles", detailBytes, 15, 15))
            }
            if (detailExtrasBytes != null) {
                add(
                    file(
                        OfflineFileRole.DETAIL_EXTRAS,
                        "countries/$id-detail-extras-$build.pmtiles",
                        detailExtrasBytes,
                        15,
                        15
                    )
                )
            }
            if (elevationBytes != null) {
                add(file(OfflineFileRole.ELEVATION, "countries/$id-elevation-$build.pmtiles", elevationBytes, 12, 12))
            }
        }
        return OfflineCountry(
            id = id,
            build = build,
            schema = OfflineCountryManifest.READABLE_SCHEMA,
            bounds = OfflineBounds(13.6, 45.45, 16.6, 46.85),
            files = files
        )
    }

    private fun file(role: OfflineFileRole, path: String, bytes: Long, minZoom: Int, maxZoom: Int): OfflineCountryFile =
        OfflineCountryFile(
            role = role,
            path = path,
            byteCount = bytes,
            sha256 = "0".repeat(64),
            minimumZoom = minZoom,
            maximumZoom = maxZoom
        )
}

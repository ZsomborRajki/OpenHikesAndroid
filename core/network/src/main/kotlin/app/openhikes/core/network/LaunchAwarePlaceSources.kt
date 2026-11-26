package app.openhikes.core.network

import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.model.CommonsPhotoCredit
import app.openhikes.core.model.CommonsPhotoLicense
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.PlaceSearchArea
import app.openhikes.core.model.PlaceSearchOutage
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.TrailPlaceFact
import app.openhikes.core.model.TrailPlaceOsm
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.TrailPointQuery
import app.openhikes.core.model.TrailPointSearchResult
import app.openhikes.core.model.TrailPointSource
import app.openhikes.core.model.WikidataFetchResult
import app.openhikes.core.model.WikidataPhoto
import app.openhikes.core.model.WikidataPlace
import app.openhikes.core.model.WikidataPlaceSource
import app.openhikes.core.model.WikipediaArticle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic UI-test scenario names parsed from `ui_test_trail_points` launch extras.
 */
object TrailPointsScenarios {
    const val SEEDED = "seeded"
    const val REFUSED = "refused"
}

/**
 * Production and launch-aware [TrailPointSource]: asks pre-built `OHPS` squares first, falls back
 * to Overpass when uncovered/unreachable, and honors debug UI test scenarios without reaching the
 * network during tests.
 */
@Singleton
internal class LaunchAwareTrailPointSource @Inject constructor(
    private val placeSquareDataSource: PlaceSquareDataSource,
    private val overpassPlaceDataSource: OverpassPlaceDataSource,
    private val launchConfigHolder: LaunchConfigHolder
) : TrailPointSource {
    override val isAvailable: Boolean
        get() {
            val config = launchConfigHolder.current.value
            return !config.isUiTesting || config.trailPointsScenarioName != null
        }

    override suspend fun places(area: PlaceSearchArea, symbols: Set<TrailPlaceSymbol>): TrailPointSearchResult {
        val config = launchConfigHolder.current.value
        return when (config.trailPointsScenarioName) {
            SCENARIO_SEEDED -> TrailPointSearchResult.Places(
                SEEDED_PLACES.filter { place -> place.symbolKind?.let { it in symbols } ?: true }
            )
            SCENARIO_REFUSED -> TrailPointSearchResult.Failed(PlaceSearchOutage.OFFLINE)
            null -> if (config.isUiTesting) {
                TrailPointSearchResult.Places(emptyList())
            } else {
                queryLive(area, symbols)
            }
            else -> TrailPointSearchResult.Places(emptyList())
        }
    }

    override suspend fun cachedPlaces(area: PlaceSearchArea, limit: Int): List<TrailPoint> {
        val config = launchConfigHolder.current.value
        if (config.isUiTesting || limit <= 0) return emptyList()
        val boxes = TrailPointQuery.searchBoxes(area)
        val fromSquares = placeSquareDataSource.cachedPlacesInBoxes(boxes, TrailPlaceSymbol.entries.toSet())
            .filter { area.contains(it.coordinate) }
        val fromOverpass = overpassPlaceDataSource.readCachedPlaces(area, limit)
        return if (fromSquares.isEmpty()) {
            fromOverpass
        } else {
            val seen = HashSet<String>()
            (fromSquares + fromOverpass)
                .filter { place ->
                    val key = place.osm?.elementKey
                    key == null || seen.add(key)
                }
                .sortedBy { RouteGeometry.distanceMeters(area.center, it.coordinate) }
                .take(limit)
        }
    }

    private suspend fun queryLive(area: PlaceSearchArea, symbols: Set<TrailPlaceSymbol>): TrailPointSearchResult {
        val boxes = TrailPointQuery.searchBoxes(area)
        if (boxes.isEmpty() || symbols.isEmpty()) {
            return TrailPointSearchResult.Places(emptyList())
        }
        return when (val squareAnswer = placeSquareDataSource.placesInBoxes(boxes, symbols)) {
            is PlaceSquaresAnswer.Places -> TrailPointSearchResult.Places(squareAnswer.places)
            PlaceSquaresAnswer.Uncovered,
            is PlaceSquaresAnswer.Unreachable -> overpassPlaceDataSource.queryPlaces(boxes, symbols)
        }
    }

    companion object {
        const val SCENARIO_SEEDED = "seeded"
        const val SCENARIO_REFUSED = "refused"
        const val SEEDED_WIKIDATA_ITEM = "Q4115189"
        private val EMPTY_HIKE_ID = HikeId("unassigned")

        internal val SEEDED_PLACES: List<TrailPoint> = listOf(
            TrailPoint(
                id = "seed-osm-way-9100001",
                hikeId = EMPTY_HIKE_ID,
                coordinate = GeoCoordinate(latitude = 47.718823, longitude = 12.831149),
                name = "Thumsee Car Park",
                symbol = TrailPlaceSymbol.PARKING.rawValue,
                osm = TrailPlaceOsm(
                    elementType = "way",
                    elementId = 9_100_001L,
                    facts = listOf(TrailPlaceFact(TrailPlaceFact.Kind.FEE, "yes"))
                )
            ),
            TrailPoint(
                id = "seed-osm-node-9100002",
                hikeId = EMPTY_HIKE_ID,
                coordinate = GeoCoordinate(latitude = 47.719219, longitude = 12.830877),
                name = "",
                symbol = TrailPlaceSymbol.VIEWPOINT.rawValue,
                osm = TrailPlaceOsm(
                    elementType = "node",
                    elementId = 9_100_002L,
                    facts = listOf(TrailPlaceFact(TrailPlaceFact.Kind.ELEVATION, "532"))
                )
            ),
            TrailPoint(
                id = "seed-osm-node-9100003",
                hikeId = EMPTY_HIKE_ID,
                coordinate = GeoCoordinate(latitude = 47.724008, longitude = 12.829098),
                name = "Lakeshore Hut",
                symbol = TrailPlaceSymbol.SHELTER.rawValue,
                osm = TrailPlaceOsm(
                    elementType = "node",
                    elementId = 9_100_003L,
                    facts = listOf(
                        TrailPlaceFact(TrailPlaceFact.Kind.ELEVATION, "540"),
                        TrailPlaceFact(TrailPlaceFact.Kind.OPENING_HOURS, "May-Oct 10:00-18:00")
                    )
                )
            ),
            TrailPoint(
                id = "seed-osm-node-9100004",
                hikeId = EMPTY_HIKE_ID,
                coordinate = GeoCoordinate(latitude = 47.7215, longitude = 12.841363),
                name = "Thumsee Kopf",
                symbol = TrailPlaceSymbol.SUMMIT.rawValue,
                osm = TrailPlaceOsm(
                    elementType = "node",
                    elementId = 9_100_004L,
                    facts = listOf(TrailPlaceFact(TrailPlaceFact.Kind.ELEVATION, "712")),
                    wikidata = SEEDED_WIKIDATA_ITEM
                )
            )
        )
    }
}

/**
 * Production and launch-aware [WikidataPlaceSource] returning deterministic Thumsee Kopf Wikipedia
 * data under `trailPointsScenarioName = "seeded"` and never reaching Wikimedia during other tests.
 */
@Singleton
internal class LaunchAwareWikidataPlaceSource @Inject constructor(
    private val httpSource: HttpWikidataPlaceSource,
    private val launchConfigHolder: LaunchConfigHolder
) : WikidataPlaceSource {
    override suspend fun place(item: String): WikidataFetchResult {
        val config = launchConfigHolder.current.value
        return when {
            config.trailPointsScenarioName == LaunchAwareTrailPointSource.SCENARIO_SEEDED -> {
                if (item == LaunchAwareTrailPointSource.SEEDED_WIKIDATA_ITEM) {
                    WikidataFetchResult.Found(seededPlace())
                } else {
                    WikidataFetchResult.Found(null)
                }
            }
            config.isUiTesting -> WikidataFetchResult.Found(null)
            else -> httpSource.place(item)
        }
    }

    private fun seededPlace(): WikidataPlace = WikidataPlace(
        item = LaunchAwareTrailPointSource.SEEDED_WIKIDATA_ITEM,
        article = WikipediaArticle(
            title = "Thumsee Kopf",
            extract = "Thumsee Kopf is a wooded summit above the Thumsee, a small lake west of " +
                "Bad Reichenhall. A path climbs to it from the lake's eastern shore.",
            url = "https://en.wikipedia.org/wiki/Wikipedia:Sandbox",
            language = "en"
        ),
        photo = WikidataPhoto(
            credit = CommonsPhotoCredit(
                author = "Lena Gruber",
                license = CommonsPhotoLicense(
                    name = "CC BY 4.0",
                    url = "https://creativecommons.org/licenses/by/4.0/",
                    sourceUrl = "https://commons.wikimedia.org/wiki/File:Seeded_place_photo.jpg"
                )
            ),
            imageBytes = SEEDED_PNG_BYTES
        )
    )

    private companion object {
        val SEEDED_PNG_BYTES: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
            0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
            0x54, 0x08, 0xD7.toByte(), 0x63, 0x60, 0xA8.toByte(), 0x67, 0x00,
            0x00, 0x00, 0x84.toByte(), 0x00, 0x81.toByte(), 0x7E, 0xC6.toByte(), 0xF4.toByte(),
            0xB2.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
            0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
        )
    }
}

package app.openhikes.core.testing

import app.openhikes.core.model.CommonsPhotoCredit
import app.openhikes.core.model.CommonsPhotoLicense
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.PlaceSearchArea
import app.openhikes.core.model.PlaceSearchOutage
import app.openhikes.core.model.TrailPlaceFact
import app.openhikes.core.model.TrailPlaceOsm
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.TrailPointSearchResult
import app.openhikes.core.model.TrailPointSource
import app.openhikes.core.model.WikidataFetchResult
import app.openhikes.core.model.WikidataPhoto
import app.openhikes.core.model.WikidataPlace
import app.openhikes.core.model.WikidataPlaceSource
import app.openhikes.core.model.WikipediaArticle

/**
 * Deterministic pure-JVM [TrailPointSource] matching iOS `SeededTrailPointSource`:
 * three places on the Thumsee loop and one summit (`Thumsee Kopf`) 420 m off it.
 */
class FakeTrailPointSource(
    private val placesList: List<TrailPoint> = SEEDED_THUMSEE_PLACES,
    private val cachedList: List<TrailPoint> = emptyList(),
    override val isAvailable: Boolean = true,
    var nextOutage: PlaceSearchOutage? = null
) : TrailPointSource {
    var searchCount: Int = 0
        private set

    var lastRequestedSymbols: Set<TrailPlaceSymbol>? = null
        private set

    override suspend fun places(area: PlaceSearchArea, symbols: Set<TrailPlaceSymbol>): TrailPointSearchResult {
        searchCount += 1
        lastRequestedSymbols = symbols
        val outage = nextOutage
        return if (outage != null) {
            TrailPointSearchResult.Failed(outage)
        } else {
            val filtered = placesList.filter { place -> place.symbolKind?.let { it in symbols } ?: true }
            TrailPointSearchResult.Places(filtered)
        }
    }

    override suspend fun cachedPlaces(area: PlaceSearchArea, limit: Int): List<TrailPoint> = cachedList.take(limit)

    companion object {
        const val SEEDED_WIKIDATA_ITEM = "Q4115189"
        private val UNASSIGNED_HIKE_ID = HikeId("unassigned")

        val SEEDED_THUMSEE_PLACES: List<TrailPoint> = listOf(
            TrailPoint(
                id = "seed-osm-way-9100001",
                hikeId = UNASSIGNED_HIKE_ID,
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
                hikeId = UNASSIGNED_HIKE_ID,
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
                hikeId = UNASSIGNED_HIKE_ID,
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
                hikeId = UNASSIGNED_HIKE_ID,
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
 * Deterministic pure-JVM [WikidataPlaceSource] matching iOS `SeededWikidataPlaceSource`.
 */
class FakeWikidataPlaceSource(
    private val entries: Map<String, WikidataFetchResult> = mapOf(
        FakeTrailPointSource.SEEDED_WIKIDATA_ITEM to WikidataFetchResult.Found(seededThumseeKopf())
    )
) : WikidataPlaceSource {
    var requestCount: Int = 0
        private set

    override suspend fun place(item: String): WikidataFetchResult {
        requestCount += 1
        return entries[item] ?: WikidataFetchResult.Found(null)
    }

    companion object {
        const val SEEDED_AUTHOR = "Lena Gruber"
        const val SEEDED_LICENSE_NAME = "CC BY 4.0"
        private const val SEEDED_ARTICLE_URL = "https://en.wikipedia.org/wiki/Wikipedia:Sandbox"
        private const val SEEDED_LICENSE_URL = "https://creativecommons.org/licenses/by/4.0/"
        private const val SEEDED_FILE_PAGE = "https://commons.wikimedia.org/wiki/File:Seeded_place_photo.jpg"

        /**
         * Minimal valid 1x1 PNG image payload so BitmapFactory decodes cleanly in JVM/Robolectric
         * and instrumented tests without touching the network.
         */
        val MINIMAL_PNG_BYTES: ByteArray = byteArrayOf(
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

        fun seededThumseeKopf(): WikidataPlace = WikidataPlace(
            item = FakeTrailPointSource.SEEDED_WIKIDATA_ITEM,
            article = WikipediaArticle(
                title = "Thumsee Kopf",
                extract = "Thumsee Kopf is a wooded summit above the Thumsee, a small lake west of " +
                    "Bad Reichenhall. A path climbs to it from the lake's eastern shore.",
                url = SEEDED_ARTICLE_URL,
                language = "en"
            ),
            photo = WikidataPhoto(
                credit = CommonsPhotoCredit(
                    author = SEEDED_AUTHOR,
                    license = CommonsPhotoLicense(
                        name = SEEDED_LICENSE_NAME,
                        url = SEEDED_LICENSE_URL,
                        sourceUrl = SEEDED_FILE_PAGE
                    )
                ),
                imageBytes = MINIMAL_PNG_BYTES
            )
        )
    }
}

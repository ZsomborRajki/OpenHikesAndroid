package app.openhikes.core.model

/**
 * A single party credited on a map surface along with the license/project URL required by terms.
 */
data class MapAttributionCredit(
    val title: String,
    val prefix: String,
    val url: String,
    val compactTitle: String = title
) {
    val plainText: String
        get() = "$prefix $title"
}

/**
 * Ordered attribution credits required for the currently rendered map and active overlays.
 */
data class MapAttribution(val credits: List<MapAttributionCredit>) {
    val plainText: String
        get() = credits.joinToString(separator = ", ") { it.plainText }

    val compactText: String
        get() = if (credits.isEmpty()) {
            ""
        } else {
            "© " + credits.joinToString(separator = ", ") { it.compactTitle }
        }

    companion object {
        private val openFreeMap = MapAttributionCredit(
            title = "OpenFreeMap",
            prefix = "Maps ©",
            url = "https://openfreemap.org",
            compactTitle = "OpenFreeMap"
        )

        private val openMapTiles = MapAttributionCredit(
            title = "OpenMapTiles",
            prefix = "©",
            url = "https://www.openmaptiles.org/",
            compactTitle = "OpenMapTiles"
        )

        private val openStreetMap = MapAttributionCredit(
            title = "OpenStreetMap contributors",
            prefix = "Data ©",
            url = "https://www.openstreetmap.org/copyright",
            compactTitle = "OpenStreetMap"
        )

        private val esaWorldCover = MapAttributionCredit(
            title = "ESA WorldCover",
            prefix = "Land cover ©",
            url = "https://esa-worldcover.org",
            compactTitle = "ESA WorldCover"
        )

        private val waymarkedTrails = MapAttributionCredit(
            title = "Waymarked Trails",
            prefix = "Hiking routes ©",
            url = "https://hiking.waymarkedtrails.org",
            compactTitle = "Waymarked Trails"
        )

        fun forOpenFreeMap(showsHikingRoutes: Boolean): MapAttribution {
            val list = buildList {
                add(openFreeMap)
                add(openMapTiles)
                add(openStreetMap)
                if (showsHikingRoutes) {
                    add(waymarkedTrails)
                }
            }
            return MapAttribution(list)
        }

        fun forOpenHikes(showsHikingRoutes: Boolean): MapAttribution {
            val list = buildList {
                add(openStreetMap)
                add(esaWorldCover)
                if (showsHikingRoutes) {
                    add(waymarkedTrails)
                }
            }
            return MapAttribution(list)
        }

        fun forStyle(mapStyleId: String, showsHikingRoutes: Boolean): MapAttribution = when (mapStyleId) {
            MapStyleId.OPENFREEMAP -> forOpenFreeMap(showsHikingRoutes)
            else -> forOpenHikes(showsHikingRoutes)
        }
    }
}

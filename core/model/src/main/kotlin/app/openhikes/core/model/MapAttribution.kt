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

    /**
     * Appends credits from [previews] that are not already present (matched by license [MapAttributionCredit.url]),
     * preserving first-seen order so Map Modes credits every pictured style and sample image.
     */
    fun including(previews: List<MapAttribution>): MapAttribution {
        val seenUrls = LinkedHashSet<String>()
        val merged = ArrayList<MapAttributionCredit>()
        for (credit in credits + previews.flatMap { it.credits }) {
            if (seenUrls.add(credit.url)) {
                merged.add(credit)
            }
        }
        return MapAttribution(merged)
    }

    companion object {
        internal val openFreeMap = MapAttributionCredit(
            title = "OpenFreeMap",
            prefix = "Maps ©",
            url = "https://openfreemap.org",
            compactTitle = "OpenFreeMap"
        )

        internal val openMapTiles = MapAttributionCredit(
            title = "OpenMapTiles",
            prefix = "©",
            url = "https://www.openmaptiles.org/",
            compactTitle = "OpenMapTiles"
        )

        internal val openStreetMap = MapAttributionCredit(
            title = "OpenStreetMap contributors",
            prefix = "Data ©",
            url = "https://www.openstreetmap.org/copyright",
            compactTitle = "OpenStreetMap"
        )

        internal val esaWorldCover = MapAttributionCredit(
            title = "ESA WorldCover",
            prefix = "Land cover ©",
            url = "https://esa-worldcover.org",
            compactTitle = "ESA WorldCover"
        )

        internal val waymarkedTrails = MapAttributionCredit(
            title = "Waymarked Trails",
            prefix = "Hiking routes ©",
            url = "https://hiking.waymarkedtrails.org",
            compactTitle = "Waymarked Trails"
        )

        internal val stadiaMaps = MapAttributionCredit(
            title = "Stadia Maps",
            prefix = "©",
            url = "https://stadiamaps.com/",
            compactTitle = "Stadia Maps"
        )

        internal val stamenDesign = MapAttributionCredit(
            title = "Stamen Design",
            prefix = "©",
            url = "https://stamen.com/",
            compactTitle = "Stamen Design"
        )

        internal val thunderforest = MapAttributionCredit(
            title = "Thunderforest",
            prefix = "Maps ©",
            url = "https://www.thunderforest.com",
            compactTitle = "Thunderforest"
        )

        fun forOpenFreeMap(showsHikingRoutes: Boolean): MapAttribution = withOptionalHikingRoutes(
            base = listOf(openFreeMap, openMapTiles, openStreetMap),
            showsHikingRoutes = showsHikingRoutes
        )

        fun forOpenHikes(showsHikingRoutes: Boolean): MapAttribution = withOptionalHikingRoutes(
            base = listOf(openStreetMap, esaWorldCover),
            showsHikingRoutes = showsHikingRoutes
        )

        fun forOpenStreetMapRaster(showsHikingRoutes: Boolean): MapAttribution = withOptionalHikingRoutes(
            base = listOf(openStreetMap),
            showsHikingRoutes = showsHikingRoutes
        )

        fun forStadiaOutdoors(showsHikingRoutes: Boolean): MapAttribution = withOptionalHikingRoutes(
            base = listOf(stadiaMaps, openMapTiles, openStreetMap),
            showsHikingRoutes = showsHikingRoutes
        )

        fun forStamenTerrain(showsHikingRoutes: Boolean): MapAttribution = withOptionalHikingRoutes(
            base = listOf(stadiaMaps, stamenDesign, openMapTiles, openStreetMap),
            showsHikingRoutes = showsHikingRoutes
        )

        fun forThunderforestOutdoors(showsHikingRoutes: Boolean): MapAttribution = withOptionalHikingRoutes(
            base = listOf(thunderforest, openStreetMap),
            showsHikingRoutes = showsHikingRoutes
        )

        fun forStyle(mapStyleId: String, showsHikingRoutes: Boolean): MapAttribution = when (mapStyleId) {
            MapStyleId.OPENFREEMAP -> forOpenFreeMap(showsHikingRoutes)
            MapStyleId.OSM_RASTER -> forOpenStreetMapRaster(showsHikingRoutes)
            MapStyleId.STADIA_OUTDOORS -> forStadiaOutdoors(showsHikingRoutes)
            MapStyleId.STAMEN_TERRAIN -> forStamenTerrain(showsHikingRoutes)
            MapStyleId.THUNDERFOREST_OUTDOORS -> forThunderforestOutdoors(showsHikingRoutes)
            else -> forOpenHikes(showsHikingRoutes)
        }

        /**
         * Credits displayed along the foot of Map Modes: [selected] and its active hiking-routes layer,
         * followed by every other pictured usable card and the two Hiking Routes sample images
         * (OpenStreetMap and Waymarked Trails style).
         */
        fun forMapModes(
            selected: MapStyle,
            showsHikingRoutes: Boolean,
            picturedStyles: List<MapStyle>
        ): MapAttribution {
            val active = forStyle(selected.id, showsHikingRoutes)
            val routeSampleCredits = MapAttribution(listOf(openStreetMap, waymarkedTrails))
            val previewCredits = picturedStyles.map { it.attribution } + routeSampleCredits
            return active.including(previewCredits)
        }

        private fun withOptionalHikingRoutes(
            base: List<MapAttributionCredit>,
            showsHikingRoutes: Boolean
        ): MapAttribution {
            val list = buildList {
                addAll(base)
                if (showsHikingRoutes) {
                    add(waymarkedTrails)
                }
            }
            return MapAttribution(list)
        }
    }
}

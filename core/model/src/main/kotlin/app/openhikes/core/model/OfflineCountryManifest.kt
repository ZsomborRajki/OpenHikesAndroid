package app.openhikes.core.model

import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Offline country catalog manifest (`https://tiles.openhikes.app/countries/manifest.json`)
 * shared across iOS and Android OpenHikes clients.
 */
@Serializable
data class OfflineCountryManifest(val version: Int, val countries: List<OfflineCountry> = emptyList()) {
    /**
     * Areas this build can safely render and store: matching schema, compatible extras generation,
     * valid area code, safe filenames prefixed with the area code, and at least a basemap file.
     * Multiple entries for the same `id` keep the newest build at the first-seen position.
     */
    val readableCountries: List<OfflineCountry>
        get() {
            if (version != CURRENT_VERSION) return emptyList()
            val readable = ArrayList<OfflineCountry>(countries.size)
            val indicesById = HashMap<String, Int>(countries.size)
            for (country in countries) {
                if (!country.isReadable) continue
                val existingIndex = indicesById[country.id]
                if (existingIndex != null) {
                    if (country.build > readable[existingIndex].build) {
                        readable[existingIndex] = country
                    }
                } else {
                    indicesById[country.id] = readable.size
                    readable.add(country)
                }
            }
            return readable
        }

    companion object {
        const val CURRENT_VERSION = 1
        const val READABLE_SCHEMA = 4
        const val READABLE_EXTRAS_GENERATION = 2
        private const val COUNTRY_CODE_LENGTH = 2
        private val REGION_CODE_LENGTH_RANGE = 1..3

        /**
         * Validates an ISO 3166-1 alpha-2 country code (`AT`) or ISO 3166-2 region code (`US-CO`).
         */
        fun isAreaCode(code: String): Boolean {
            val parts = code.split('-')
            val hasValidCountry = parts.isNotEmpty() &&
                parts.size <= 2 &&
                parts[0].length == COUNTRY_CODE_LENGTH &&
                parts[0].all(::isAsciiUpper)
            val hasValidRegion = parts.size == 1 ||
                (
                    parts.size == 2 &&
                        parts[1].length in REGION_CODE_LENGTH_RANGE &&
                        parts[1].all { ch -> isAsciiUpper(ch) || ch in '0'..'9' }
                    )
            return hasValidCountry && hasValidRegion
        }

        private fun isAsciiUpper(ch: Char): Boolean = ch in 'A'..'Z'
    }
}

/**
 * A downloadable offline country or split-country region (such as US states, CA provinces, RU regions).
 */
@Serializable
data class OfflineCountry(
    val id: String,
    val group: String? = null,
    val names: Map<String, String> = emptyMap(),
    val build: String,
    val schema: Int,
    val extrasGeneration: Int? = null,
    val bounds: OfflineBounds,
    val outline: List<List<List<Double>>> = emptyList(),
    val files: List<OfflineCountryFile> = emptyList()
) {
    val hasAreaCode: Boolean
        get() {
            if (!OfflineCountryManifest.isAreaCode(id)) return false
            val parent = group ?: return !id.contains('-')
            return id.startsWith("$parent-") &&
                OfflineCountryManifest.isAreaCode(parent) &&
                !parent.contains('-')
        }

    val isReadable: Boolean
        get() {
            val effectiveExtrasGen = extrasGeneration ?: 1
            return schema == OfflineCountryManifest.READABLE_SCHEMA &&
                effectiveExtrasGen in 1..OfflineCountryManifest.READABLE_EXTRAS_GENERATION &&
                hasAreaCode &&
                files.any { it.role == OfflineFileRole.BASEMAP } &&
                files.all { it.hasSafeName && it.fileName.startsWith("$id-") }
        }

    fun files(withDetail: Boolean): List<OfflineCountryFile> = files.filter { withDetail || !it.role.isDetail }

    fun byteCount(withDetail: Boolean): Long = files(withDetail).sumOf { it.byteCount }

    /**
     * Localized display name of this country or region itself (without parent country suffix).
     */
    fun localizedName(locale: Locale = Locale.getDefault()): String {
        val language = locale.language.lowercase(Locale.ROOT)
        return names[language]?.takeIf { it.isNotBlank() }
            ?: names["en"]?.takeIf { it.isNotBlank() }
            ?: localeDisplayCountryOrNull(locale)
            ?: id
    }

    private fun localeDisplayCountryOrNull(locale: Locale): String? {
        if (group != null) return null
        val localized = Locale.Builder().setRegion(id).build().getDisplayCountry(locale)
        return localized.takeIf { it.isNotBlank() && !it.equals(id, ignoreCase = true) }
    }

    /**
     * Localized display name of the parent country when [group] is non-null, or `null` for standalone countries.
     */
    fun groupLocalizedName(locale: Locale = Locale.getDefault()): String? {
        val parentCode = group ?: return null
        val localized = Locale.Builder().setRegion(parentCode).build().getDisplayCountry(locale)
        return localized.takeIf { it.isNotBlank() } ?: parentCode
    }

    /**
     * Full display name: `<Country>` for standalone countries, `<Region>, <Country>` for split-country regions.
     */
    fun fullName(locale: Locale = Locale.getDefault()): String {
        val ownName = localizedName(locale)
        val parentName = groupLocalizedName(locale)
        return if (parentName != null) "$ownName, $parentName" else ownName
    }

    /**
     * Point-in-polygon check against [bounds] and each polygon ring in [outline] using even-odd ray casting.
     */
    fun contains(longitude: Double, latitude: Double): Boolean {
        if (!bounds.contains(longitude = longitude, latitude = latitude)) return false
        return outline.any { ring -> ringContains(ring, longitude, latitude) }
    }

    private companion object {
        fun ringContains(ring: List<List<Double>>, x: Double, y: Double): Boolean {
            if (ring.isEmpty()) return false
            var inside = false
            var previous = ring.last()
            for (vertex in ring) {
                if (vertex.size >= 2 && previous.size >= 2) {
                    val x1 = previous[0]
                    val y1 = previous[1]
                    val x2 = vertex[0]
                    val y2 = vertex[1]
                    if ((y1 > y) != (y2 > y) && x < (x2 - x1) * (y - y1) / (y2 - y1) + x1) {
                        inside = !inside
                    }
                }
                previous = vertex
            }
            return inside
        }
    }
}

/**
 * Single PMTiles file belonging to an [OfflineCountry] download.
 */
@Serializable
data class OfflineCountryFile(
    val role: OfflineFileRole,
    val path: String,
    @SerialName("bytes")
    val byteCount: Long,
    val sha256: String,
    val minimumZoom: Int,
    val maximumZoom: Int
) {
    /**
     * Terminal file name used under the device offline directory (`<area>[-detail][-extras]-<yyyymmdd>.pmtiles`).
     */
    val fileName: String
        get() = path.substringAfterLast('/', missingDelimiterValue = path)

    /**
     * Strict allowlist check ensuring network-supplied file names can never traverse directories.
     */
    val hasSafeName: Boolean
        get() {
            if (!fileName.endsWith(FILE_EXTENSION)) return false
            val stem = fileName.removeSuffix(FILE_EXTENSION)
            val parts = stem.split('-').toMutableList()
            if (parts.size < 2) return false
            val build = parts.removeAt(parts.lastIndex)
            if (build.length != BUILD_DATE_LENGTH || !build.all { it in '0'..'9' }) return false
            if (parts.lastOrNull() == ROLE_TRAILS_SEGMENT || parts.lastOrNull() == ROLE_ELEVATION_SEGMENT) {
                parts.removeAt(parts.lastIndex)
            } else {
                if (parts.lastOrNull() == ROLE_EXTRAS_SEGMENT) parts.removeAt(parts.lastIndex)
                if (parts.lastOrNull() == ROLE_DETAIL_SEGMENT) parts.removeAt(parts.lastIndex)
            }
            return OfflineCountryManifest.isAreaCode(parts.joinToString("-"))
        }

    companion object {
        const val FILE_EXTENSION = ".pmtiles"
        private const val BUILD_DATE_LENGTH = 8
        private const val ROLE_EXTRAS_SEGMENT = "extras"
        private const val ROLE_DETAIL_SEGMENT = "detail"
        private const val ROLE_TRAILS_SEGMENT = "trails"
        private const val ROLE_ELEVATION_SEGMENT = "elevation"
    }
}

/**
 * Role of a PMTiles archive within an offline country bundle.
 */
@Serializable
enum class OfflineFileRole(val wireName: String) {
    @SerialName("basemap")
    BASEMAP("basemap"),

    @SerialName("extras")
    EXTRAS("extras"),

    @SerialName("detail")
    DETAIL("detail"),

    @SerialName("detail-extras")
    DETAIL_EXTRAS("detail-extras"),

    @SerialName("elevation")
    ELEVATION("elevation");

    val isDetail: Boolean
        get() = this == DETAIL || this == DETAIL_EXTRAS

    companion object {
        fun fromWireNameOrNull(raw: String): OfflineFileRole? = entries.firstOrNull { it.wireName == raw }
    }
}

/**
 * Geographic bounding box carried by an [OfflineCountry].
 */
@Serializable
data class OfflineBounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    fun contains(longitude: Double, latitude: Double): Boolean = longitude in west..east && latitude in south..north

    fun intersects(box: GeoBoundingBox): Boolean =
        west <= box.eastLon && east >= box.westLon && south <= box.northLat && north >= box.southLat

    fun toGeoBoundingBox(): GeoBoundingBox = GeoBoundingBox(
        southLat = south.coerceIn(-MAX_LATITUDE, MAX_LATITUDE),
        westLon = west.coerceIn(-MAX_LONGITUDE, MAX_LONGITUDE),
        northLat = north.coerceIn(-MAX_LATITUDE, MAX_LATITUDE),
        eastLon = east.coerceIn(-MAX_LONGITUDE, MAX_LONGITUDE)
    )

    private companion object {
        const val MAX_LATITUDE = 90.0
        const val MAX_LONGITUDE = 180.0
    }
}

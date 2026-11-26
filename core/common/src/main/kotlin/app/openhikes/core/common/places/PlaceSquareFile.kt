package app.openhikes.core.common.places

import app.openhikes.core.common.routing.PackedEnvelope
import app.openhikes.core.common.routing.PackedFileException
import app.openhikes.core.common.routing.PackedReader
import app.openhikes.core.common.routing.PackedStringTable
import app.openhikes.core.common.routing.PackedWriter
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.PlaceSearchBox
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint

/**
 * Pure-JVM codec for OpenHikes `OHPS` v2 pre-built OpenStreetMap places square files served by
 * `tiles.openhikes.app/places-<build>/9/<col>/<row>.bin`.
 */
data class PlaceSquareFile(val places: List<Place>, val coordinateScale: Int = STANDARD_COORDINATE_SCALE) {
    enum class Element(val code: Int, val osmName: String) {
        NODE(0, "node"),
        WAY(1, "way"),
        RELATION(2, "relation");

        companion object {
            fun fromCode(code: Int): Element? = entries.firstOrNull { it.code == code }
        }
    }

    data class Point(val latitude: Int, val longitude: Int)

    data class Box(val south: Int, val west: Int, val north: Int, val east: Int) {
        constructor(point: Point) : this(point.latitude, point.longitude, point.latitude, point.longitude)

        val center: Point
            get() = Point(
                latitude = ((south.toLong() + north.toLong()) / 2L).toInt(),
                longitude = ((west.toLong() + east.toLong()) / 2L).toInt()
            )
    }

    data class Place(val element: Element, val id: Long, val box: Box, val tags: Map<String, String>) {
        constructor(element: Element, id: Long, point: Point, tags: Map<String, String>) : this(
            element = element,
            id = id,
            box = Box(point),
            tags = tags
        )

        val point: Point
            get() = box.center
    }

    fun encode(): ByteArray = PackedEnvelope.encode(MAGIC, FORMAT_VERSION, encodeBody())

    fun encodeBody(): ByteArray {
        val strings = PackedStringTable()
        val written = PackedWriter()
        var previousId = 0L
        var previousLat = 0L
        var previousLon = 0L
        val sorted = places.sortedWith(compareBy<Place>({ it.element.code }, { it.id }))
        for (place in sorted) {
            val box = place.box
            written.writeVarint(place.element.code.toLong())
            written.writeStep(place.id - previousId)
            previousId = place.id
            written.writeStep(box.south.toLong() - previousLat)
            written.writeStep(box.west.toLong() - previousLon)
            previousLat = box.south.toLong()
            previousLon = box.west.toLong()
            written.writeVarint(box.north.toLong() - box.south.toLong())
            written.writeVarint(box.east.toLong() - box.west.toLong())
            val sortedTags = place.tags.entries.sortedBy { it.key }
            written.writeVarint(sortedTags.size.toLong())
            for ((key, value) in sortedTags) {
                written.writeVarint(strings.indexOf(key))
                written.writeVarint(strings.indexOf(value))
            }
        }
        val body = PackedWriter()
        body.writeVarint(coordinateScale.toLong())
        body.writeStrings(strings)
        body.writeVarint(sorted.size.toLong())
        body.writeRaw(written.toByteArray())
        return body.toByteArray()
    }

    companion object {
        const val FORMAT_VERSION = 2
        const val STANDARD_COORDINATE_SCALE = 1_000_000
        private val MAGIC = byteArrayOf('O'.code.toByte(), 'H'.code.toByte(), 'P'.code.toByte(), 'S'.code.toByte())
        private const val POLE_DEGREES = 90L
        private const val ANTIMERIDIAN_DEGREES = 180L

        fun decode(bytes: ByteArray): PlaceSquareFile {
            val body = PackedEnvelope.decode(bytes, MAGIC, FORMAT_VERSION)
            val reader = PackedReader(body)
            val scaleLong = reader.readVarint()
            if (scaleLong <= 0L || scaleLong > Int.MAX_VALUE.toLong()) throw PackedFileException.Corrupt
            val strings = reader.readStrings()
            val count = reader.readCount()
            val cursor = PlaceCursor(scaleLong)
            val places = List(count) { readPlace(reader, strings, cursor) }
            if (!reader.isAtEnd) throw PackedFileException.Corrupt
            return PlaceSquareFile(places = places, coordinateScale = scaleLong.toInt())
        }

        /**
         * Extracts deduplicated [TrailPoint] instances from [squares] matching [symbols] and
         * [predicate] over each place's pin center and ground bounding box.
         */
        fun extractPlaces(
            squares: Iterable<PlaceSquareFile>,
            showing: Set<TrailPlaceSymbol>,
            predicate: (pin: GeoCoordinate, ground: PlaceSearchBox) -> Boolean
        ): List<TrailPoint> {
            val seen = HashSet<String>()
            val found = ArrayList<TrailPoint>()
            for (square in squares) {
                val scale = square.coordinateScale.toDouble()
                for (entry in square.places) {
                    val pin = GeoCoordinate(
                        latitude = entry.point.latitude / scale,
                        longitude = entry.point.longitude / scale
                    )
                    val ground = PlaceSearchBox(
                        south = entry.box.south / scale,
                        west = entry.box.west / scale,
                        north = entry.box.north / scale,
                        east = entry.box.east / scale
                    )
                    val key = "${entry.element.osmName}/${entry.id}"
                    if (predicate(pin, ground) && seen.add(key)) {
                        TrailPointDecoding.place(
                            elementType = entry.element.osmName,
                            id = entry.id,
                            latitude = pin.latitude,
                            longitude = pin.longitude,
                            tags = entry.tags,
                            showing = showing
                        )?.let(found::add)
                    }
                }
            }
            return found
        }

        private fun readPlace(reader: PackedReader, strings: List<String>, cursor: PlaceCursor): Place {
            val elementCode = reader.readVarint()
            val element = if (elementCode in 0L..Byte.MAX_VALUE.toLong()) {
                Element.fromCode(elementCode.toInt())
            } else {
                null
            } ?: throw PackedFileException.Corrupt
            cursor.id += reader.readStep()
            cursor.latitude += reader.readStep()
            cursor.longitude += reader.readStep()
            val height = reader.readVarint()
            val width = reader.readVarint()
            val box = cursor.box(height = height, width = width) ?: throw PackedFileException.Corrupt
            val tagCount = reader.readCount()
            val tags = LinkedHashMap<String, String>(tagCount)
            repeat(tagCount) {
                val key = reader.readIndexedString(strings)
                val value = reader.readIndexedString(strings)
                tags[key] = value
            }
            return Place(element = element, id = cursor.id, box = box, tags = tags)
        }
    }

    private class PlaceCursor(private val unitsPerDegree: Long) {
        var id: Long = 0L
        var latitude: Long = 0L
        var longitude: Long = 0L

        fun box(height: Long, width: Long): Box? {
            val span = 2L * ANTIMERIDIAN_DEGREES * unitsPerDegree
            val inRange = height in 0L..span && width in 0L..span
            val sw = if (inRange) pointOrNull(latitude, longitude) else null
            val ne = if (sw != null) pointOrNull(latitude + height, longitude + width) else null
            return if (sw != null && ne != null) {
                Box(south = sw.latitude, west = sw.longitude, north = ne.latitude, east = ne.longitude)
            } else {
                null
            }
        }

        private fun pointOrNull(lat: Long, lon: Long): Point? {
            val pole = POLE_DEGREES * unitsPerDegree
            val antimeridian = ANTIMERIDIAN_DEGREES * unitsPerDegree
            val valid = lat in -pole..pole &&
                lon in -antimeridian..antimeridian &&
                lat in Int.MIN_VALUE..Int.MAX_VALUE &&
                lon in Int.MIN_VALUE..Int.MAX_VALUE
            return if (valid) Point(lat.toInt(), lon.toInt()) else null
        }
    }
}

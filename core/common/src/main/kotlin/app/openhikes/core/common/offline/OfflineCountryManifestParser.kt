package app.openhikes.core.common.offline

import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.OfflineBounds
import app.openhikes.core.model.OfflineCountry
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineCountryManifest
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.model.PendingCountryDownload
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Dual-mode JSON parser for offline country manifests and device-local ownership files.
 *
 * - [parseCatalog] decodes remote server manifests leniently: files whose `role` is unknown to
 *   this build are dropped instead of rejecting the entire catalog (forward compatibility).
 * - [parseStrictRecords] and [parseStrictPending] decode device-owned `index.json` / `pending.json`
 *   strictly: an unrecognized role or field corruption throws so an older build never sweeps files
 *   owned by a newer build.
 */
object OfflineCountryManifestParser {
    private val catalogJson = Json {
        ignoreUnknownKeys = true
    }

    private val strictJson = Json {
        ignoreUnknownKeys = false
    }

    fun parseCatalog(rawJson: String): OfflineCountryManifest {
        val root = catalogJson.parseToJsonElement(rawJson).jsonObject
        val version = root["version"]?.jsonPrimitive?.int
            ?: throw SerializationException("Missing manifest version")
        val countriesArray = root["countries"]?.jsonArray
            ?: throw SerializationException("Missing manifest countries array")
        val countries = countriesArray.map { element -> parseCatalogCountry(element.jsonObject) }
        return OfflineCountryManifest(version = version, countries = countries)
    }

    fun parseStrictRecords(rawJson: String): List<InstalledCountryRecord> = strictJson.decodeFromString(rawJson)

    fun parseStrictPending(rawJson: String): List<PendingCountryDownload> = strictJson.decodeFromString(rawJson)

    fun encodeRecords(records: List<InstalledCountryRecord>): String = strictJson.encodeToString(records)

    fun encodePending(pending: List<PendingCountryDownload>): String = strictJson.encodeToString(pending)

    fun encodeManifest(manifest: OfflineCountryManifest): String = catalogJson.encodeToString(manifest)

    private fun parseCatalogCountry(obj: JsonObject): OfflineCountry {
        val id = obj.requireString("id")
        val group = obj["group"]?.takeUnless { it.jsonPrimitive.content == "null" }?.jsonPrimitive?.content
        val namesObj = obj["names"]?.jsonObject ?: JsonObject(emptyMap())
        val names = namesObj.mapValues { (_, v) -> v.jsonPrimitive.content }
        val build = obj.requireString("build")
        val schema = obj["schema"]?.jsonPrimitive?.int ?: throw SerializationException("Missing schema")
        val extrasGeneration = obj["extrasGeneration"]?.let { el ->
            if (el.jsonPrimitive.isString) null else el.jsonPrimitive.int
        }
        val boundsObj = obj["bounds"]?.jsonObject ?: throw SerializationException("Missing bounds")
        val bounds = OfflineBounds(
            west = boundsObj.requireDouble("west"),
            south = boundsObj.requireDouble("south"),
            east = boundsObj.requireDouble("east"),
            north = boundsObj.requireDouble("north")
        )
        val outline = parseOutline(obj["outline"])
        val files = parseLenientFiles(obj["files"]?.jsonArray ?: JsonArray(emptyList()))
        return OfflineCountry(
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

    private fun parseOutline(element: JsonElement?): List<List<List<Double>>> {
        if (element == null) return emptyList()
        return element.jsonArray.map { ringElement ->
            ringElement.jsonArray.map { pairElement ->
                pairElement.jsonArray.map { coord -> coord.jsonPrimitive.double }
            }
        }
    }

    private fun parseLenientFiles(filesArray: JsonArray): List<OfflineCountryFile> =
        filesArray.mapNotNull { fileElement ->
            val fileObj = fileElement.jsonObject
            val rawRole = fileObj.requireString("role")
            val role = OfflineFileRole.fromWireNameOrNull(rawRole) ?: return@mapNotNull null
            OfflineCountryFile(
                role = role,
                path = fileObj.requireString("path"),
                byteCount = fileObj["bytes"]?.jsonPrimitive?.long
                    ?: throw SerializationException("Missing bytes"),
                sha256 = fileObj.requireString("sha256"),
                minimumZoom = fileObj["minimumZoom"]?.jsonPrimitive?.int
                    ?: throw SerializationException("Missing minimumZoom"),
                maximumZoom = fileObj["maximumZoom"]?.jsonPrimitive?.int
                    ?: throw SerializationException("Missing maximumZoom")
            )
        }
}

private fun JsonObject.requireString(key: String): String =
    this[key]?.jsonPrimitive?.content ?: throw SerializationException("Missing string field '$key'")

private fun JsonObject.requireDouble(key: String): Double =
    this[key]?.jsonPrimitive?.double ?: throw SerializationException("Missing double field '$key'")

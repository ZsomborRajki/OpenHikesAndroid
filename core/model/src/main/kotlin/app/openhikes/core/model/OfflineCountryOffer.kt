package app.openhikes.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * Optional parts of an offline country bundle controlled by switches in Offline Maps:
 * zoom-15 vector tiles (*Extra Detail*) and Copernicus DEM squares (*Elevation Data*).
 */
enum class OfflineCountryExtra {
    DETAIL,
    ELEVATION;

    companion object {
        /**
         * Returns the switch-controlled extra corresponding to [role], or `null` for files every
         * download takes automatically.
         */
        fun fromRoleOrNull(role: OfflineFileRole): OfflineCountryExtra? = when {
            role.isDetail -> DETAIL
            role.isElevation -> ELEVATION
            else -> null
        }

        /**
         * Collects the distinct optional extras represented by [files].
         */
        fun fromFiles(files: List<OfflineCountryFile>): Set<OfflineCountryExtra> =
            files.mapNotNull { fromRoleOrNull(it.role) }.toSet()
    }
}

/**
 * Actionable offer shown on a downloaded country's row when *Extra Detail* or *Elevation Data*
 * differs from what is stored on disk: either files to add or files to delete.
 */
data class OfflineCountryOffer(val extras: Set<OfflineCountryExtra>, val files: List<OfflineCountryFile>) {
    /**
     * Bytes this offer will download when adding, or free on disk when removing.
     */
    val byteCount: Long
        get() = files.sumOf { it.byteCount }
}

/**
 * Strict, backward-compatible serializer for [InstalledCountryRecord].
 *
 * Always writes `hasDetail` and `hasElevation` explicitly on encode. On decode, rejects unknown
 * keys under strict JSON settings while defaulting a missing `hasElevation` field (from indexes
 * written before the *Elevation Data* switch existed) to whether `country.files` includes an
 * elevation archive so existing elevation files are preserved instead of swept.
 */
object InstalledCountryRecordSerializer : KSerializer<InstalledCountryRecord> {
    private const val INDEX_COUNTRY = 0
    private const val INDEX_INSTALLED_AT = 1
    private const val INDEX_HAS_DETAIL = 2
    private const val INDEX_HAS_ELEVATION = 3

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "app.openhikes.core.model.InstalledCountryRecord"
    ) {
        element<OfflineCountry>("country")
        element<Long>("installedAtEpochMillis")
        element<Boolean>("hasDetail", isOptional = true)
        element<Boolean>("hasElevation", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: InstalledCountryRecord) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, INDEX_COUNTRY, OfflineCountry.serializer(), value.country)
            encodeLongElement(descriptor, INDEX_INSTALLED_AT, value.installedAtEpochMillis)
            encodeBooleanElement(descriptor, INDEX_HAS_DETAIL, value.hasDetail)
            encodeBooleanElement(descriptor, INDEX_HAS_ELEVATION, value.hasElevation)
        }
    }

    override fun deserialize(decoder: Decoder): InstalledCountryRecord = decoder.decodeStructure(descriptor) {
        var country: OfflineCountry? = null
        var installedAt: Long? = null
        var hasDetail = false
        var hasElevation: Boolean? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                INDEX_COUNTRY -> country = decodeSerializableElement(
                    descriptor,
                    INDEX_COUNTRY,
                    OfflineCountry.serializer()
                )

                INDEX_INSTALLED_AT -> installedAt = decodeLongElement(descriptor, INDEX_INSTALLED_AT)
                INDEX_HAS_DETAIL -> hasDetail = decodeBooleanElement(descriptor, INDEX_HAS_DETAIL)
                INDEX_HAS_ELEVATION -> hasElevation = decodeBooleanElement(descriptor, INDEX_HAS_ELEVATION)
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("Unexpected index $index")
            }
        }
        val resolvedCountry = country ?: throw SerializationException("Missing field 'country'")
        val resolvedInstalledAt = installedAt ?: throw SerializationException("Missing field 'installedAtEpochMillis'")
        val resolvedHasElevation = hasElevation ?: resolvedCountry.files.any { it.role.isElevation }
        InstalledCountryRecord(
            country = resolvedCountry,
            installedAtEpochMillis = resolvedInstalledAt,
            hasDetail = hasDetail,
            hasElevation = resolvedHasElevation
        )
    }
}

package app.openhikes.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Relation
import app.openhikes.core.database.entity.HikeEntity
import app.openhikes.core.database.entity.HikeRouteChunkEntity

data class HikeWithRouteRow(
    @Embedded val hike: HikeEntity,
    @Relation(parentColumn = "id", entityColumn = "hike_id")
    val routeChunks: List<HikeRouteChunkEntity>
)

data class HikeMapRouteChunkRow(
    @ColumnInfo(name = "hike_id") val hikeId: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @ColumnInfo(name = "chunk_blob") val chunkBlob: ByteArray,
    @ColumnInfo(name = "tint_hex") val tintHex: String,
    @ColumnInfo(name = "route_border_hex") val routeBorderHex: String,
    @ColumnInfo(name = "route_width_dp") val routeWidthDp: Double,
    @ColumnInfo(name = "route_line_pattern_id") val routeLinePatternId: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HikeMapRouteChunkRow) return false
        return hikeId == other.hikeId &&
            chunkIndex == other.chunkIndex &&
            chunkBlob.contentEquals(other.chunkBlob) &&
            tintHex == other.tintHex &&
            routeBorderHex == other.routeBorderHex &&
            routeWidthDp == other.routeWidthDp &&
            routeLinePatternId == other.routeLinePatternId
    }

    override fun hashCode(): Int {
        var result = hikeId.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + chunkBlob.contentHashCode()
        result = 31 * result + tintHex.hashCode()
        result = 31 * result + routeBorderHex.hashCode()
        result = 31 * result + routeWidthDp.hashCode()
        result = 31 * result + routeLinePatternId.hashCode()
        return result
    }
}

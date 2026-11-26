package app.openhikes.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.model.GeoCoordinate
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher

internal class TestPhotoLibraryMirror : PhotoLibraryMirror {
    val savedCalls = mutableListOf<Pair<Long, GeoCoordinate?>>()
    var nextAssetId: String = "mirrored-asset-1"
    var nextFailure: Throwable? = null

    override suspend fun saveCameraPhoto(
        jpegBytes: ByteArray,
        capturedAtEpochMillis: Long,
        coordinate: GeoCoordinate?
    ): Result<String> {
        savedCalls.add(capturedAtEpochMillis to coordinate)
        val failure = nextFailure?.also { nextFailure = null }
        return if (failure != null) Result.failure(failure) else Result.success(nextAssetId)
    }
}

internal class TestPhotoFileIo : PhotoFileIo {
    val pickedByUri = HashMap<String, PickedImagePayload>()
    val writtenArchiveByUri = HashMap<String, ByteArray>()

    override suspend fun readPickedUri(uriString: String): PickedImagePayload? = pickedByUri[uriString]

    override suspend fun writeBytesToUri(uriString: String, bytes: ByteArray): Result<Unit> {
        writtenArchiveByUri[uriString] = bytes
        return Result.success(Unit)
    }
}

internal fun createTestPhotoStore(
    dispatcher: CoroutineDispatcher,
    context: Context = ApplicationProvider.getApplicationContext()
): Pair<File, HikePhotoStore> {
    val dir = File(context.cacheDir, "test_photos_${UUID.randomUUID()}").apply { mkdirs() }
    return dir to DiskHikePhotoStore(
        rootDir = dir,
        ioDispatcher = dispatcher,
        defaultDispatcher = dispatcher
    )
}

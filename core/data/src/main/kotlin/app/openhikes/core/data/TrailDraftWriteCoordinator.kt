package app.openhikes.core.data

import app.openhikes.core.common.ApplicationScope
import app.openhikes.core.model.StoredTrailDraft
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Serializes device-local Trail Maker draft writes on [applicationScope] so writes survive
 * navigation pops and a reopened editor always awaits pending writes from the previous session
 * before reading the stored draft.
 */
@Singleton
class TrailDraftWriteCoordinator @Inject constructor(@ApplicationScope private val applicationScope: CoroutineScope) {
    private val lock = Any()
    private var latestWriteJob: Job? = null

    fun enqueueWrite(repository: TrailDraftRepository, snapshot: StoredTrailDraft): Job = synchronized(lock) {
        val previous = latestWriteJob
        val job = applicationScope.launch {
            previous?.join()
            if (snapshot.isEmpty) {
                repository.clearDraft()
            } else {
                repository.saveDraft(snapshot)
            }
        }
        latestWriteJob = job
        job
    }

    suspend fun writeAndAwait(repository: TrailDraftRepository, snapshot: StoredTrailDraft) {
        enqueueWrite(repository, snapshot).join()
    }

    suspend fun awaitPendingWrites() {
        while (true) {
            val pending = synchronized(lock) { latestWriteJob } ?: return
            if (pending.isCompleted) return
            pending.join()
        }
    }
}

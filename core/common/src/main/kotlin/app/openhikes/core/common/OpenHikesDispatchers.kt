package app.openhikes.core.common

import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Qualifier for disk, database, and network I/O coroutine dispatchers.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Qualifier for CPU-bound background computation dispatchers.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * Qualifier for main-thread coroutine dispatchers.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/**
 * Qualifier for the process-scoped application [kotlinx.coroutines.CoroutineScope].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Injectable abstraction over coroutine dispatchers for deterministic testing.
 */
interface OpenHikesDispatchers {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

/**
 * Production implementation backed by standard Kotlin coroutine dispatchers.
 */
class DefaultOpenHikesDispatchers(
    override val io: CoroutineDispatcher = Dispatchers.IO,
    override val default: CoroutineDispatcher = Dispatchers.Default,
    override val main: CoroutineDispatcher = Dispatchers.Main
) : OpenHikesDispatchers

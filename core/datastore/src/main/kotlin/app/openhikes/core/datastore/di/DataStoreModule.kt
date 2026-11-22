package app.openhikes.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.openhikes.core.common.ApplicationScope
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.datastore.UserPreferences
import app.openhikes.core.datastore.UserPreferencesSerializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.UUID
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Provides the singleton [DataStore] for [UserPreferences].
 */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    private const val PREFERENCES_FILE_NAME = "user_preferences.pb"
    private const val ISOLATED_DIR_NAME = "openhikes-test-datastore"

    @Provides
    @Singleton
    fun provideUserPreferencesDataStore(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        @ApplicationScope applicationScope: CoroutineScope,
        launchConfigHolder: LaunchConfigHolder
    ): DataStore<UserPreferences> = DataStoreFactory.create(
        serializer = UserPreferencesSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { UserPreferences.getDefaultInstance() },
        scope = CoroutineScope(applicationScope.coroutineContext + ioDispatcher),
        produceFile = {
            if (launchConfigHolder.current.value.useIsolatedDataStore) {
                File(context.cacheDir, "$ISOLATED_DIR_NAME/user_preferences_${UUID.randomUUID()}.pb")
            } else {
                context.dataStoreFile(PREFERENCES_FILE_NAME)
            }
        }
    )
}

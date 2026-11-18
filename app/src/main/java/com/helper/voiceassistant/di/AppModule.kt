package com.helper.voiceassistant.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.helper.voiceassistant.assistant.AudioPipeline
import com.helper.voiceassistant.assistant.AudioRouting
import com.helper.voiceassistant.assistant.ChatClient
import com.helper.voiceassistant.assistant.HeadsetButtons
import com.helper.voiceassistant.manager.MediaButtonManager
import com.helper.voiceassistant.assistant.Speaker
import com.helper.voiceassistant.assistant.SttEngine
import com.helper.voiceassistant.data.audio.AudioStreamProcessor
import com.helper.voiceassistant.data.deepseek.DeepSeekApiClient
import com.helper.voiceassistant.data.local.AppDatabase
import com.helper.voiceassistant.data.local.ConversationDao
import com.helper.voiceassistant.data.local.KeystoreSecretStore
import com.helper.voiceassistant.data.repository.ConversationRepositoryImpl
import com.helper.voiceassistant.data.repository.DataStoreSettingsRepository
import com.helper.voiceassistant.data.stt.OfflineTranscriber
import com.helper.voiceassistant.data.tts.TtsSpeaker
import com.helper.voiceassistant.domain.repository.ConversationRepository
import com.helper.voiceassistant.domain.repository.SecretStore
import com.helper.voiceassistant.domain.repository.SettingsRepository
import com.helper.voiceassistant.manager.AudioDeviceManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // retries are handled explicitly with backoff
        .build()

    @Provides @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "assistant.db").build()

    @Provides
    fun conversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    /** Serialized dispatcher: controller handlers never run concurrently. */
    @Provides @Singleton @Named("controller")
    fun controllerDispatcher(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    @Provides @Named("recordingsDir")
    fun recordingsDir(@ApplicationContext context: Context): File = File(context.filesDir, "recordings")
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun settingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun conversationRepository(impl: ConversationRepositoryImpl): ConversationRepository
    @Binds abstract fun secretStore(impl: KeystoreSecretStore): SecretStore
    @Binds abstract fun audioPipeline(impl: AudioStreamProcessor): AudioPipeline
    @Binds abstract fun audioRouting(impl: AudioDeviceManager): AudioRouting
    @Binds abstract fun sttEngine(impl: OfflineTranscriber): SttEngine
    @Binds abstract fun chatClient(impl: DeepSeekApiClient): ChatClient
    @Binds abstract fun speaker(impl: TtsSpeaker): Speaker
    @Binds abstract fun headsetButtons(impl: MediaButtonManager): HeadsetButtons
}

package com.helper.voiceassistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.helper.voiceassistant.domain.repository.SettingsRepository
import com.helper.voiceassistant.util.AppLanguage
import com.helper.voiceassistant.util.LogBuffer
import com.helper.voiceassistant.util.tr
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class VoiceAssistantApp : Application() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        Timber.plant(LogBuffer.Tree())
        createChannel()
        // Apply the interface language chosen in Settings; UI recomposes instantly on change.
        MainScope().launch {
            settingsRepository.settings.map { it.uiLanguage }.distinctUntilChanged().collect {
                AppLanguage.setting = it
                createChannel() // updates the channel name shown in system settings
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            tr("Assistant microphone", "Микрофон ассистента"),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = tr("Shown while the assistant listens to the headset microphone", "Показывается, пока ассистент слушает микрофон гарнитуры") }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "assistant_microphone"
    }
}

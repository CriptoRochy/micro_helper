package com.helper.voiceassistant.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.helper.voiceassistant.MainActivity
import com.helper.voiceassistant.R
import com.helper.voiceassistant.VoiceAssistantApp
import com.helper.voiceassistant.assistant.AssistantController
import com.helper.voiceassistant.domain.model.AssistantState
import com.helper.voiceassistant.domain.model.AssistantStatus
import com.helper.voiceassistant.presentation.components.label
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import com.helper.voiceassistant.util.tr

/**
 * Foreground service (type = microphone) that keeps the assistant alive while the UI is in the
 * background. It must be started while the app is in the foreground (Android 14+ forbids starting a
 * microphone FGS from the background), and it is not restarted after force-stop or reboot.
 */
@AndroidEntryPoint
class AudioCaptureService : LifecycleService() {

    @Inject lateinit var controller: AssistantController

    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                Timber.i("Stop requested")
                controller.stop()
                shutdown()
                return START_NOT_STICKY
            }
            else -> startAssistant()
        }
        return START_NOT_STICKY
    }

    private fun startAssistant() {
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(controller.status.value), type)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException (no RECORD_AUDIO, background start).
            Timber.e(e, "Foreground service start refused")
            controller.reportStartFailure(tr("Android did not allow the microphone service to start. Open the app and press Start.", "Android не разрешил запустить службу микрофона. Откройте приложение и нажмите «Запустить»."))
            stopSelf()
            return
        }
        acquireWakeLock()
        observeStatus()
        controller.start()
    }

    private fun observeStatus() {
        if (observing) return
        observing = true
        lifecycleScope.launch {
            var wasActive = false
            controller.status.distinctUntilChanged { a, b -> a.state == b.state && a.error == b.error && a.info == b.info }
                .collect { status ->
                    if (status.state.isActive) wasActive = true
                    if (wasActive && status.state == AssistantState.IDLE) {
                        shutdown()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
                    }
                }
        }
    }

    private fun buildNotification(status: AssistantStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AudioCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = status.error?.message ?: status.info ?: tr("Microphone in use · ${status.state.label()}", "Микрофон используется · ${status.state.label()}")
        return NotificationCompat.Builder(this, VoiceAssistantApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_assistant)
            .setContentTitle(tr("Voice assistant is active", "Голосовой ассистент активен") + " — " + status.state.label())
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, tr("Stop", "Стоп"), stop)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        // Keeps the CPU running so capture and network calls continue with the screen off.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceAssistant:capture")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun shutdown() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        controller.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 42
        const val ACTION_START = "com.helper.voiceassistant.START"
        const val ACTION_STOP = "com.helper.voiceassistant.STOP"
        private const val WAKE_LOCK_TIMEOUT_MS = 8 * 60 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, AudioCaptureService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AudioCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}

package com.helper.voiceassistant.manager

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.helper.voiceassistant.assistant.AudioRouting
import com.helper.voiceassistant.assistant.RouteResult
import com.helper.voiceassistant.domain.model.AudioDeviceKind
import com.helper.voiceassistant.domain.model.AudioDeviceSummary
import com.helper.voiceassistant.domain.model.AudioRouteInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.helper.voiceassistant.util.tr

/**
 * Detects headsets and routes microphone + playback to them.
 *
 * Bluetooth: only the HFP/SCO (or LE Audio) profile carries the headset microphone. A2DP is
 * playback-only, so a "Bluetooth A2DP" device is never chosen as input. Bringing up SCO requires
 * MODE_IN_COMMUNICATION and either setCommunicationDevice() (Android 12+) or startBluetoothSco()
 * (older). While SCO is active most phones route playback through SCO too, therefore TTS uses
 * voice-communication attributes in that case (see TtsSpeaker). Behaviour still differs between
 * vendors — always verify on the actual device.
 */
@Singleton
class AudioDeviceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: AudioSessionManager,
    private val callBridge: HeadsetCallBridge,
) : AudioRouting {

    private val bridgeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    init {
        bridgeScope.launch { callBridge.hungUp.collect { _headsetVoiceEnded.tryEmit(Unit) } }
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _route = MutableStateFlow(AudioRouteInfo())
    override val route: StateFlow<AudioRouteInfo> = _route.asStateFlow()
    private val _inputLost = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val inputLost: SharedFlow<Unit> = _inputLost.asSharedFlow()
    private val _headsetAdded = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val headsetAdded: SharedFlow<Unit> = _headsetAdded.asSharedFlow()
    private val _headsetVoiceEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val headsetVoiceEnded: SharedFlow<Unit> = _headsetVoiceEnded.asSharedFlow()

    // ── Bluetooth "voice recognition" link (HFP AT+BVRA): like a voice assistant, not a call. ──
    @Volatile private var headsetProxy: BluetoothHeadset? = null
    /** Device whose voice-recognition link we opened; null when none (or we closed it ourselves). */
    @Volatile private var vrDevice: BluetoothDevice? = null
    @Volatile private var vrConnected: CompletableDeferred<Boolean>? = null

    private val proxyListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) headsetProxy = proxy as BluetoothHeadset
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) headsetProxy = null
        }
    }

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            Timber.i("Headset audio state → %d (voice link %s)", state, if (vrDevice != null) "ours" else "none")
            when (state) {
                BluetoothHeadset.STATE_AUDIO_CONNECTED -> vrConnected?.complete(true)
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> {
                    vrConnected?.complete(false)
                    if (vrDevice != null) {
                        // Not closed by us → the headset ended it (button tap = AT+BVRA=0).
                        Timber.i("Headset closed the voice link")
                        vrDevice = null
                        _headsetVoiceEnded.tryEmit(Unit)
                    }
                }
            }
        }
    }

    @Volatile private var activeInputId: Int? = null
    @Volatile private var legacyScoStarted = false
    @Volatile private var monitoring = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            Timber.i("Audio devices added: %s", addedDevices.joinToString { describe(it) })
            refresh()
            if (addedDevices.any { it.isSource && isHeadsetInput(it.type) }) _headsetAdded.tryEmit(Unit)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            Timber.i("Audio devices removed: %s", removedDevices.joinToString { describe(it) })
            val active = activeInputId
            refresh()
            if (active != null && removedDevices.any { it.id == active }) {
                activeInputId = null
                _inputLost.tryEmit(Unit)
            }
        }
    }

    override fun startMonitoring() {
        if (monitoring) return
        monitoring = true
        audioManager.registerAudioDeviceCallback(callback, mainHandler)
        runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?.getProfileProxy(context, proxyListener, BluetoothProfile.HEADSET)
        }.onFailure { Timber.w(it, "No Bluetooth headset profile") }
        ContextCompat.registerReceiver(
            context, scoReceiver, IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED, // sent by the Bluetooth process
        )
        refresh()
    }

    override fun stopMonitoring() {
        if (!monitoring) return
        monitoring = false
        audioManager.unregisterAudioDeviceCallback(callback)
        runCatching { context.unregisterReceiver(scoReceiver) }
        headsetProxy?.let { p ->
            runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.closeProfileProxy(BluetoothProfile.HEADSET, p) }
        }
        headsetProxy = null
    }

    /**
     * Opens the headset microphone the way voice assistants do (HFP voice recognition) instead of a
     * "call". Many headsets then end it themselves when their button is tapped, which we report via
     * [headsetVoiceEnded]. Returns false if unsupported; the caller then falls back to the call route.
     */
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested before the assistant starts.
    private suspend fun startVoiceRecognitionLink(): Boolean {
        val proxy = headsetProxy ?: return false.also { Timber.w("Voice link: no headset proxy") }
        return try {
            val device = proxy.connectedDevices.firstOrNull() ?: return false
            val waiter = CompletableDeferred<Boolean>()
            vrConnected = waiter
            if (!proxy.startVoiceRecognition(device)) {
                Timber.w("Voice link: startVoiceRecognition refused")
                return false
            }
            vrDevice = device
            val ok = withTimeoutOrNull(4_000) { waiter.await() } ?: proxy.isAudioConnected(device)
            Timber.i("Voice link to %s: %s", device.name, if (ok) "open" else "failed")
            if (!ok) stopVoiceRecognitionLink()
            ok
        } catch (e: SecurityException) {
            Timber.w(e, "Voice link: no Bluetooth permission")
            false
        } finally {
            vrConnected = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopVoiceRecognitionLink() {
        val device = vrDevice ?: return
        vrDevice = null // first, so the resulting "audio disconnected" broadcast is not taken as a tap
        runCatching { headsetProxy?.stopVoiceRecognition(device) }.onFailure { Timber.w(it, "stopVoiceRecognition") }
    }

    fun refresh() {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != TYPE_REMOTE_SUBMIX }
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val headsetIn = inputs.firstOrNull { isHeadsetInput(it.type) }
        val headsetOut = outputs.firstOrNull { isHeadsetOutput(it.type) }
        val current = _route.value
        _route.value = current.copy(
            headsetConnected = headsetIn != null || headsetOut != null,
            outputName = if (activeInputId == null) headsetOut?.let { label(it) } else current.outputName,
            availableInputs = inputs.map { AudioDeviceSummary(it.id, label(it), kindOf(it.type), true) },
        )
    }

    /** Picks the input (preferred → BT headset → wired → USB → built-in) and brings up the route. */
    override suspend fun connect(preferredInputId: Int?, useHeadsetMic: Boolean, voiceRecognition: Boolean): RouteResult {
        refresh()
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val best = inputs.firstOrNull { preferredInputId != null && preferredInputId >= 0 && it.id == preferredInputId }
            ?: inputs.minByOrNull { priority(it.type) }
        val headsetMicAvailable = inputs.any { needsCommunicationRoute(it.type) }
        // Without the headset mic: keep the Bluetooth headset in music mode and listen with the phone.
        val chosen = if (!useHeadsetMic && best != null && needsCommunicationRoute(best.type)) {
            inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?: best
        } else best
        if (chosen == null) {
            _route.value = _route.value.copy(inputName = null, inputKind = null)
            return RouteResult(_route.value, null, headsetMicAvailable)
        }

        var usesComm = false
        var outputName: String? = null
        if (needsCommunicationRoute(chosen.type)) {
            // Preferred: a short VoIP call, so the headset's "hang up" tap reaches us. Fallback: voice recognition link.
            val call = voiceRecognition && chosen.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && callBridge.begin()
            val vr = voiceRecognition && !call && chosen.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && startVoiceRecognitionLink()
            session.enterCommunicationMode()
            usesComm = if (vr) {
                outputName = label(chosen); true // SCO already up; AudioRecord uses it as preferred device
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val target = audioManager.availableCommunicationDevices.firstOrNull { sameHeadset(it, chosen) }
                if (target != null && audioManager.setCommunicationDevice(target)) {
                    outputName = label(target); true
                } else false
            } else {
                startLegacySco()
            }
            if (!usesComm) Timber.w("Could not activate headset communication route for %s", describe(chosen))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isHeadsetInput(chosen.type)) {
            // Wired/USB headsets route automatically; still prefer them for communication streams.
            audioManager.availableCommunicationDevices.firstOrNull { it.type == chosen.type }
                ?.let { audioManager.setCommunicationDevice(it); outputName = label(it) }
        }

        activeInputId = chosen.id
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // Without the call route, Bluetooth playback goes over A2DP (music mode), not SCO.
        val headsetOut = (if (!usesComm) outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } else null)
            ?: outputs.firstOrNull { isHeadsetOutput(it.type) }
        _route.value = _route.value.copy(
            inputName = label(chosen),
            inputKind = kindOf(chosen.type),
            outputName = outputName ?: headsetOut?.let { label(it) } ?: tr("Phone speaker", "Динамик телефона"),
            usesCommunicationRoute = usesComm,
        )
        Timber.i("Route: in=%s comm=%s out=%s", describe(chosen), usesComm, _route.value.outputName)
        return RouteResult(_route.value, chosen.id, headsetMicAvailable)
    }

    override fun disconnect() {
        activeInputId = null
        callBridge.end()
        stopVoiceRecognitionLink()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        if (legacyScoStarted) {
            @Suppress("DEPRECATION")
            runCatching { audioManager.stopBluetoothSco(); audioManager.isBluetoothScoOn = false }
            legacyScoStarted = false
        }
        session.restoreMode()
        _route.value = _route.value.copy(inputName = null, inputKind = null, usesCommunicationRoute = false)
        refresh()
    }

    /** Android 8–11: start SCO and wait (≤ 4 s) for the SCO audio link to come up. */
    @Suppress("DEPRECATION")
    private suspend fun startLegacySco(): Boolean {
        if (!audioManager.isBluetoothScoAvailableOffCall) return false
        val connected = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> connected.complete(true)
                    AudioManager.SCO_AUDIO_STATE_ERROR -> connected.complete(false)
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return try {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            legacyScoStarted = true
            withTimeoutOrNull(4_000) { connected.await() } ?: false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun sameHeadset(comm: AudioDeviceInfo, input: AudioDeviceInfo): Boolean {
        if (comm.type == input.type) return true
        // SCO input ↔ SCO output; LE headset input ↔ LE headset output.
        return comm.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && input.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    private fun needsCommunicationRoute(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    private fun isHeadsetInput(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    private fun isHeadsetOutput(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    private fun priority(type: Int): Int = when {
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 0
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET -> 1
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> 2
        type == AudioDeviceInfo.TYPE_USB_HEADSET -> 3
        type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> 4
        else -> 10
    }

    private fun kindOf(type: Int): AudioDeviceKind = when {
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioDeviceKind.BLUETOOTH_SCO
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioDeviceKind.BLUETOOTH_A2DP
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioDeviceKind.BLE_HEADSET
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioDeviceKind.WIRED_HEADSET
        type == AudioDeviceInfo.TYPE_USB_HEADSET -> AudioDeviceKind.USB_HEADSET
        type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioDeviceKind.BUILTIN_MIC
        type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioDeviceKind.BUILTIN_SPEAKER
        else -> AudioDeviceKind.OTHER
    }

    private fun label(d: AudioDeviceInfo): String {
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
        val kind = kindOf(d.type).label
        return if (name != null) "$name · $kind" else kind
    }

    private fun describe(d: AudioDeviceInfo) = "${d.productName}(type=${d.type},id=${d.id},src=${d.isSource})"

    private companion object {
        /** AudioDeviceInfo.TYPE_REMOTE_SUBMIX (constant 25; the field is only declared from API 31). */
        const val TYPE_REMOTE_SUBMIX = 25
    }
}

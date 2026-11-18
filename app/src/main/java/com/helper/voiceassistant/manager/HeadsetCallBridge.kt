package com.helper.voiceassistant.manager

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.helper.voiceassistant.util.tr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Presents a recording to the system as a short self-managed VoIP call (like messenger calls).
 *
 * Why: while a Bluetooth headset microphone is open the headset is in "call mode" and sends no media
 * buttons; a tap sends the HFP "hang up" command (AT+CHUP) instead. Android passes that command only
 * to an ongoing call, so with this call active a tap reaches us as [Connection.onDisconnect] and is
 * reported through [hungUp].
 */
@Singleton
class HeadsetCallBridge @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val handle = PhoneAccountHandle(ComponentName(context, AssistantConnectionService::class.java), ACCOUNT_ID)
    private var registered = false

    private val _hungUp = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** The headset (or the system) ended the call: usually a tap on the headset button. */
    val hungUp: SharedFlow<Unit> = _hungUp.asSharedFlow()

    @Volatile private var connection: AssistantConnection? = null
    @Volatile private var pending: CompletableDeferred<AssistantConnection?>? = null

    val isActive: Boolean get() = connection != null

    private fun register(): Boolean {
        if (registered) return true
        return runCatching {
            val account = PhoneAccount.builder(handle, tr("Voice assistant", "Голосовой ассистент"))
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_SIP))
                .build()
            telecom.registerPhoneAccount(account)
            registered = true
            true
        }.getOrElse { Timber.w(it, "Registering the call account failed"); false }
    }

    /** Starts the call; returns true once it is active (audio then goes through the headset). */
    @SuppressLint("MissingPermission") // MANAGE_OWN_CALLS is a normal (install-time) permission.
    suspend fun begin(): Boolean {
        if (connection != null) return true
        if (telecom == null || !register()) return false
        val waiter = CompletableDeferred<AssistantConnection?>()
        pending = waiter
        current = this
        return try {
            val extras = Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle) }
            telecom.placeCall(Uri.fromParts(PhoneAccount.SCHEME_SIP, "assistant", null), extras)
            val c = withTimeoutOrNull(3_000) { waiter.await() }
            if (c == null) Timber.w("Call for the headset microphone was not created")
            else Timber.i("Headset call active")
            c != null
        } catch (e: Exception) {
            Timber.w(e, "placeCall failed")
            false
        } finally {
            pending = null
        }
    }

    /** Ends the call ourselves (not reported as a hang-up). */
    fun end() {
        val c = connection ?: return
        connection = null
        c.endLocally()
        Timber.i("Headset call ended by the app")
    }

    internal fun onCreated(c: AssistantConnection?) {
        if (c != null) connection = c
        pending?.complete(c)
    }

    internal fun onRemoteHangup(c: AssistantConnection) {
        if (connection !== c) return
        connection = null
        Timber.i("Headset hung up the call")
        _hungUp.tryEmit(Unit)
    }

    companion object {
        private const val ACCOUNT_ID = "assistant"
        /** Telecom instantiates the service itself, so it finds the bridge through this reference. */
        @Volatile internal var current: HeadsetCallBridge? = null
    }
}

class AssistantConnection(private val bridge: HeadsetCallBridge) : Connection() {
    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
        setAddress(Uri.fromParts(PhoneAccount.SCHEME_SIP, "assistant", null), TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName(tr("Voice assistant", "Голосовой ассистент"), TelecomManager.PRESENTATION_ALLOWED)
    }

    /** A hang-up from the headset (AT+CHUP), a car kit or the system. */
    override fun onDisconnect() {
        bridge.onRemoteHangup(this)
        finish()
    }

    override fun onAbort() = onDisconnect()

    fun endLocally() = finish()

    private fun finish() {
        if (state == STATE_DISCONNECTED) return
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }
}

class AssistantConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        val bridge = HeadsetCallBridge.current
            ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        val c = AssistantConnection(bridge)
        c.setActive()
        bridge.onCreated(c)
        return c
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        Timber.w("Telecom refused the headset call")
        HeadsetCallBridge.current?.onCreated(null)
    }
}

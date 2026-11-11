package com.pulsarnav.app.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.pulsarnav.app.ble.BajajBleManager
import com.pulsarnav.app.protocol.CallState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ActiveCallInfo(
    val state: CallState,
    val callerName: String?,
    val callerNumber: String?,
    val missedCallCount: Int = 0
)

@SuppressLint("MissingPermission")
class CallManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PulsarCallManager"
        private const val LONG_PRESS_THRESHOLD_MS = 750L

        @Volatile
        private var INSTANCE: CallManager? = null

        fun getInstance(context: Context): CallManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CallManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    private val bleManager get() = BajajBleManager.getInstance(context)

    private val _callInfo = MutableStateFlow(ActiveCallInfo(CallState.NO_CALL, null, null, 0))
    val callInfo: StateFlow<ActiveCallInfo> = _callInfo

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val lookupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Volatile
    private var wasRinging = false
    @Volatile
    private var lastIncomingNumber: String? = null
    @Volatile
    private var lastIncomingName: String? = null
    private var missedCount = 0

    private var buttonPressStartTime = 0L

    private var isInitialized = false
    private var isReceiverRegistered = false
    private var registeredTelephonyCallback: Any? = null
    @Suppress("DEPRECATION")
    private var registeredPhoneStateListener: PhoneStateListener? = null
    private var lastDispatchedCallState: CallState? = null
    private var lastDispatchedCallerName: String? = null

    private val phoneStateBroadcastReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                @Suppress("DEPRECATION")
                val incomingNum = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                when (stateStr) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        if (!incomingNum.isNullOrBlank()) {
                            onIncomingNumberDetected(incomingNum)
                        } else {
                            handleCallState(TelephonyManager.CALL_STATE_RINGING, null)
                        }
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        handleCallState(TelephonyManager.CALL_STATE_OFFHOOK, incomingNum)
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        handleCallState(TelephonyManager.CALL_STATE_IDLE, null)
                    }
                }
            }
        }
    }

    fun initialize() {
        if (isInitialized) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE permission not yet granted; deferring TelephonyCallback registration")
            return
        }

        isInitialized = true

        try {
            val filter = android.content.IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            ContextCompat.registerReceiver(context, phoneStateBroadcastReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Dynamic receiver registration for PHONE_STATE: ${e.message}")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state, null)
                    }
                }
                telephonyManager?.registerTelephonyCallback(context.mainExecutor, callback)
                registeredTelephonyCallback = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state, phoneNumber)
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                registeredPhoneStateListener = listener
            }
            Log.i(TAG, "Telephony listener registered successfully")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException registering TelephonyCallback: ${e.message}")
        }
    }

    fun release() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(phoneStateBroadcastReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (registeredTelephonyCallback as? TelephonyCallback)?.let {
                    telephonyManager?.unregisterTelephonyCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                registeredPhoneStateListener?.let {
                    telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (_: Exception) {}
        registeredTelephonyCallback = null
        registeredPhoneStateListener = null
        isInitialized = false
    }

    @Synchronized
    fun onIncomingNumberDetected(number: String) {
        wasRinging = true
        val prevName = lastIncomingName
        lastIncomingNumber = number
        val fallbackName = if (lastIncomingNumber == number && !prevName.isNullOrBlank()) prevName else number
        lastIncomingName = fallbackName
        Log.i(TAG, "Incoming call from $fallbackName ($number)")

        dispatchCallUpdate(CallState.INCOMING_CALL, fallbackName, number)

        lookupExecutor.execute {
            val resolved = resolveContactName(number)
            mainHandler.post {
                if (wasRinging && lastIncomingNumber == number && resolved != lastIncomingName) {
                    lastIncomingName = resolved
                    dispatchCallUpdate(CallState.INCOMING_CALL, resolved, number)
                }
            }
        }
    }

    @Synchronized
    private fun handleCallState(state: Int, incomingNumber: String?) {
        val number = incomingNumber ?: lastIncomingNumber
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                wasRinging = true
                if (!number.isNullOrBlank()) {
                    onIncomingNumberDetected(number)
                } else {
                    dispatchCallUpdate(CallState.INCOMING_CALL, "Incoming Call", null)
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                val name = lastIncomingName ?: "Active Call"
                wasRinging = false
                dispatchCallUpdate(CallState.ACTIVE_CALL, name, number)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (wasRinging) {
                    missedCount++
                    val name = lastIncomingName ?: "Unknown"
                    val phone = lastIncomingNumber ?: ""
                    Log.i(TAG, "Missed call recorded from $name ($phone). Total missed: $missedCount")
                    bleManager.sendMissedCall(name, phone, missedCount)
                }
                wasRinging = false
                lastIncomingNumber = null
                lastIncomingName = null
                dispatchCallUpdate(CallState.NO_CALL, null, null)
            }
        }
    }

    private fun dispatchCallUpdate(state: CallState, callerName: String?, callerNumber: String?) {
        if (lastDispatchedCallState == state && lastDispatchedCallerName == callerName) {
            return
        }
        lastDispatchedCallState = state
        lastDispatchedCallerName = callerName
        _callInfo.value = ActiveCallInfo(
            state = state,
            callerName = callerName,
            callerNumber = callerNumber,
            missedCallCount = missedCount
        )
        bleManager.updateCallState(
            callState = state,
            callerName = callerName,
            callerNumber = callerNumber,
            missedCallCount = missedCount
        )
    }

    /**
     * Handlebar Button Interception:
     * - Incoming Call: Single Click (<750ms) = Accept Call, Long Press (>=750ms) = Reject Call
     * - Active Call: Long Press (>=750ms) = End Call
     * - Normal Riding (No Call): Delegated to Media Control (Single = Play/Pause, Long = Next)
     */
    fun onHandlebarButtonStateChanged(isPressed: Boolean) {
        if (isPressed) {
            buttonPressStartTime = System.currentTimeMillis()
            Log.d(TAG, "Handlebar button DOWN")
        } else {
            val duration = System.currentTimeMillis() - buttonPressStartTime
            Log.d(TAG, "Handlebar button UP (held for ${duration}ms)")
            val isLongPress = duration >= LONG_PRESS_THRESHOLD_MS

            when (_callInfo.value.state) {
                CallState.INCOMING_CALL -> {
                    if (isLongPress) {
                        Log.i(TAG, "Handlebar button LONG PRESS detected -> Rejecting Call")
                        rejectIncomingCall()
                    } else {
                        Log.i(TAG, "Handlebar button SINGLE CLICK detected -> Accepting Call")
                        acceptIncomingCall()
                    }
                }
                CallState.ACTIVE_CALL -> {
                    if (isLongPress) {
                        Log.i(TAG, "Handlebar button LONG PRESS detected -> Ending Active Call")
                        rejectIncomingCall()
                    }
                }
                else -> {
                    // No active call: Route to Media Control
                    com.pulsarnav.app.media.MediaControlManager.getInstance(context).handleHandlebarButton(isLongPress)
                }
            }
        }
    }

    fun acceptIncomingCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecomManager?.acceptRingingCall()
                Log.i(TAG, "Call accepted via TelecomManager")
                bleManager.acknowledgeCallAction(accept = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept call: ${e.message}")
        }
    }

    fun rejectIncomingCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager?.endCall()
                Log.i(TAG, "Call rejected/ended via TelecomManager")
                bleManager.acknowledgeCallAction(reject = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reject call: ${e.message}")
        }
    }

    fun simulateIncomingCall(name: String = "Dad (Mobile)", number: String = "+91 98765 43210") {
        wasRinging = true
        lastIncomingName = name
        lastIncomingNumber = number
        _callInfo.value = ActiveCallInfo(CallState.INCOMING_CALL, name, number, missedCount)
        bleManager.updateCallState(CallState.INCOMING_CALL, name, number)
    }

    fun simulateEndCall() {
        wasRinging = false
        lastIncomingName = null
        lastIncomingNumber = null
        _callInfo.value = ActiveCallInfo(CallState.NO_CALL, null, null, missedCount)
        bleManager.updateCallState(CallState.NO_CALL, null, null, missedCallCount = missedCount)
    }

    fun simulateMissedCall(name: String = "Mom", number: String = "+91 98765 12345") {
        missedCount++
        _callInfo.value = ActiveCallInfo(CallState.NO_CALL, null, null, missedCount)
        bleManager.sendMissedCall(name, number, missedCount)
    }

    private fun resolveContactName(phoneNumber: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return phoneNumber
        }
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val cursor: Cursor? = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return it.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error looking up contact name for $phoneNumber: ${e.message}")
        }
        return phoneNumber
    }
}

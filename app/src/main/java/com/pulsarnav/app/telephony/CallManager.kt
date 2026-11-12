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
import com.pulsarnav.app.debug.DebugLogRepository
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
    private val stateLock = Any()

    private var wasRinging = false
    private var wasRejectedByUser = false
    private var lastIncomingNumber: String? = null
    private var lastIncomingName: String? = null
    private var outgoingDialStartedAtMs = 0L
    private var missedCount = 0
    private var incomingCallGeneration = 0L

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

    fun onIncomingNumberDetected(number: String) {
        val (generation, shouldResolve) = synchronized(stateLock) {
            val sameIncomingNumber = wasRinging && lastIncomingNumber == number
            val prevName = lastIncomingName
            val gen = if (sameIncomingNumber) {
                incomingCallGeneration
            } else {
                wasRinging = true
                lastIncomingNumber = number
                incomingCallGeneration + 1L
            }
            incomingCallGeneration = gen
            val resolvedFallback = if (sameIncomingNumber && !prevName.isNullOrBlank()) prevName else number
            lastIncomingName = resolvedFallback
            if (!sameIncomingNumber) {
                Log.i(
                    TAG,
                    "Incoming call from ${com.pulsarnav.app.debug.DebugLogRepository.maskPersonName(resolvedFallback)} (${com.pulsarnav.app.debug.DebugLogRepository.maskPhoneNumber(number)})"
                )
            }
            dispatchCallUpdateLocked(CallState.INCOMING_CALL, resolvedFallback, number)
            Pair(gen, !sameIncomingNumber || resolvedFallback == number)
        }

        if (!shouldResolve) return

        lookupExecutor.execute {
            val resolved = resolveContactName(number)
            mainHandler.post {
                synchronized(stateLock) {
                    if (wasRinging &&
                        incomingCallGeneration == generation &&
                        lastIncomingNumber == number &&
                        resolved != lastIncomingName
                    ) {
                        lastIncomingName = resolved
                        dispatchCallUpdateLocked(CallState.INCOMING_CALL, resolved, number)
                    }
                }
            }
        }
    }

    private fun handleCallState(state: Int, incomingNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val number = synchronized(stateLock) {
                    incomingNumber?.takeIf { it.isNotBlank() } ?: lastIncomingNumber
                }
                if (!number.isNullOrBlank()) {
                    onIncomingNumberDetected(number)
                } else {
                    synchronized(stateLock) {
                        if (!wasRinging) {
                            incomingCallGeneration++
                        }
                        wasRinging = true
                        wasRejectedByUser = false
                        dispatchCallUpdateLocked(CallState.INCOMING_CALL, "Incoming Call", null)
                    }
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                synchronized(stateLock) {
                    val number = incomingNumber?.takeIf { it.isNotBlank() } ?: lastIncomingNumber
                    val isRecentOutgoing = !wasRinging &&
                        outgoingDialStartedAtMs > 0L &&
                        (android.os.SystemClock.elapsedRealtime() - outgoingDialStartedAtMs) < 2_500L
                    val targetState = if (isRecentOutgoing) CallState.OUTGOING_CALL else CallState.ACTIVE_CALL
                    val fallbackLabel = if (targetState == CallState.OUTGOING_CALL) "Dialing..." else "Active Call"
                    val name = lastIncomingName ?: fallbackLabel
                    wasRinging = false
                    incomingCallGeneration++
                    dispatchCallUpdateLocked(targetState, name, number)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                var finishedName: String? = null
                synchronized(stateLock) {
                    if (wasRinging && !wasRejectedByUser) {
                        missedCount++
                        val resolvedName = lastIncomingName ?: "Unknown"
                        val resolvedNumber = lastIncomingNumber ?: ""
                        finishedName = resolvedName
                        Log.i(
                            TAG,
                            "Missed call recorded from ${com.pulsarnav.app.debug.DebugLogRepository.maskPersonName(resolvedName)} (${com.pulsarnav.app.debug.DebugLogRepository.maskPhoneNumber(resolvedNumber)}). Total missed: $missedCount"
                        )
                        bleManager.sendMissedCall(resolvedName, resolvedNumber, missedCount)
                    }
                    wasRinging = false
                    wasRejectedByUser = false
                    outgoingDialStartedAtMs = 0L
                    lastIncomingNumber = null
                    lastIncomingName = null
                    incomingCallGeneration++
                    dispatchCallUpdateLocked(CallState.NO_CALL, null, null)
                }
                // Wait briefly for Android's CallLog ContentProvider to commit the finished call entry
                mainHandler.postDelayed({ bleManager.syncPhonebookAndCalls() }, 900L)
                if (finishedName != null) {
                    Log.d(TAG, "Scheduled recent-call log sync after missed call")
                }
            }
        }
    }

    private fun dispatchCallUpdateLocked(state: CallState, callerName: String?, callerNumber: String?) {
        if (lastDispatchedCallState == state &&
            lastDispatchedCallerName == callerName &&
            _callInfo.value.callerNumber == callerNumber
        ) {
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
        DebugLogRepository.telephony(
            "Call",
            "State=$state caller=${callerName ?: "-"} num=${DebugLogRepository.maskPhoneNumber(callerNumber)} missed=$missedCount"
        )
        bleManager.updateCallState(
            callState = state,
            callerName = callerName,
            callerNumber = callerNumber,
            missedCallCount = missedCount
        )
    }

    fun acceptIncomingCall() {
        try {
            telecomManager?.acceptRingingCall()
            Log.i(TAG, "Call accepted via TelecomManager")
            bleManager.acknowledgeCallAction(accept = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept call: ${e.message}")
        }
    }

    fun rejectIncomingCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                synchronized(stateLock) {
                    wasRejectedByUser = true
                }
                telecomManager?.endCall()
                Log.i(TAG, "Call rejected/ended via TelecomManager")
                bleManager.acknowledgeCallAction(reject = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reject call: ${e.message}")
        }
    }

    fun rejectIncomingCallWithSms() {
        val callerNumber = synchronized(stateLock) {
            wasRejectedByUser = true
            lastIncomingNumber ?: _callInfo.value.callerNumber
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager?.endCall()
                Log.i(TAG, "Call rejected with SMS intent via TelecomManager")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed ending call before reject SMS: ${e.message}")
        }
        val smsSent = ClusterPhonebookManager.getInstance(context).sendRejectSms(callerNumber)
        DebugLogRepository.telephony("Call", "Reject+SMS action (smsSent=$smsSent)")
        bleManager.acknowledgeCallAction(reject = true, rejectSms = smsSent)
    }

    fun notifyOutgoingDialFromCluster(displayName: String, rawNumber: String) {
        synchronized(stateLock) {
            wasRinging = false
            wasRejectedByUser = false
            outgoingDialStartedAtMs = android.os.SystemClock.elapsedRealtime()
            lastIncomingName = displayName
            lastIncomingNumber = rawNumber
            incomingCallGeneration++
            dispatchCallUpdateLocked(CallState.OUTGOING_CALL, displayName, rawNumber)
        }
        // Clear synthetic OUTGOING_CALL state if dialer doesn't reach OFFHOOK within 6s
        mainHandler.postDelayed({
            synchronized(stateLock) {
                if (_callInfo.value.state == CallState.OUTGOING_CALL &&
                    (android.os.SystemClock.elapsedRealtime() - outgoingDialStartedAtMs) >= 5_500L
                ) {
                    outgoingDialStartedAtMs = 0L
                    lastIncomingName = null
                    lastIncomingNumber = null
                    dispatchCallUpdateLocked(CallState.NO_CALL, null, null)
                }
            }
        }, 6_000L)
    }

    fun pushLatestMissedCallToCluster() {
        val existingMissed = synchronized(stateLock) {
            if (missedCount > 0 && !lastIncomingNumber.isNullOrBlank()) {
                Triple(lastIncomingName ?: "Missed Call", lastIncomingNumber ?: "", missedCount)
            } else null
        }
        if (existingMissed != null) {
            bleManager.sendMissedCall(existingMissed.first, existingMissed.second, existingMissed.third)
            return
        }
        lookupExecutor.execute {
            val latestMissed = ClusterPhonebookManager.getInstance(context).getLatestMissedCall()
            if (latestMissed != null) {
                val count = synchronized(stateLock) {
                    if (missedCount <= 0) missedCount = 1
                    missedCount
                }
                bleManager.sendMissedCall(latestMissed.name, latestMissed.number, count)
            }
        }
    }

    fun simulateIncomingCall(name: String = "Dad (Mobile)", number: String = "+91 98765 43210") {
        synchronized(stateLock) {
            wasRinging = true
            lastIncomingName = name
            lastIncomingNumber = number
            incomingCallGeneration++
            dispatchCallUpdateLocked(CallState.INCOMING_CALL, name, number)
        }
    }

    fun simulateEndCall() {
        synchronized(stateLock) {
            wasRinging = false
            lastIncomingName = null
            lastIncomingNumber = null
            incomingCallGeneration++
            dispatchCallUpdateLocked(CallState.NO_CALL, null, null)
        }
    }

    fun simulateMissedCall(name: String = "Mom", number: String = "+91 98765 12345") {
        synchronized(stateLock) {
            missedCount++
            lastDispatchedCallState = CallState.NO_CALL
            lastDispatchedCallerName = null
            _callInfo.value = ActiveCallInfo(CallState.NO_CALL, null, null, missedCount)
            bleManager.sendMissedCall(name, number, missedCount)
        }
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
            Log.w(TAG, "Error looking up contact name for ${com.pulsarnav.app.debug.DebugLogRepository.maskPhoneNumber(phoneNumber)}: ${e.message}")
        }
        return phoneNumber
    }
}

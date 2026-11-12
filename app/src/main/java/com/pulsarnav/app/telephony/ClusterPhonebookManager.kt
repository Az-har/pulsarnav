package com.pulsarnav.app.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.debug.DebugLogRepository
import com.pulsarnav.app.protocol.ClusterContactEntry
import com.pulsarnav.app.protocol.ClusterRecentCallEntry
import com.pulsarnav.app.protocol.OtcPacketBuilder
import com.pulsarnav.app.protocol.RecentCallStatus
import java.util.concurrent.atomic.AtomicReference

/**
 * Queries Android starred contacts & call logs for TFT cluster phonebook sync (0910/0b10),
 * resolves switchgear dialback selections (0a10 dialSource/dialIndex), places calls safely,
 * and dispatches auto-reply SMS when rejecting incoming calls with SMS.
 */
class ClusterPhonebookManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ClusterPhonebook"
        const val MAX_CLUSTER_ENTRIES = 5
        private const val SMS_DEBOUNCE_MS = 5_000L
        private const val MAX_CALLLOG_ROWS_TO_SCAN = 150
        private const val MAX_CONTACT_LOOKUPS = 15

        const val DIAL_SOURCE_FAVOURITES = 1
        const val DIAL_SOURCE_RECENTS = 2
        const val DIAL_SOURCE_MISSED = 3

        @Volatile
        private var instance: ClusterPhonebookManager? = null

        fun getInstance(context: Context): ClusterPhonebookManager {
            return instance ?: synchronized(this) {
                instance ?: ClusterPhonebookManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val cachedFavs = AtomicReference<List<ClusterContactEntry>>(emptyList())
    private val cachedRecents = AtomicReference<List<ClusterRecentCallEntry>>(emptyList())
    private val cachedMissed = AtomicReference<List<ClusterRecentCallEntry>>(emptyList())

    private val smsLock = Any()
    private var lastSmsSentElapsedMs = 0L
    private var isSendingSms = false

    fun hasReadContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun hasReadCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun hasCallPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    fun hasSendSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("Range")
    fun queryFavoriteContacts(maxCount: Int = MAX_CLUSTER_ENTRIES): List<ClusterContactEntry> {
        if (!hasReadContactsPermission()) return emptyList()
        val results = ArrayList<ClusterContactEntry>(maxCount)
        val seenNumbers = HashSet<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1",
                null,
                "${ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED} DESC, ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && results.size < maxCount) {
                    val rawNumber = if (numCol >= 0) cursor.getString(numCol).orEmpty().trim() else ""
                    val dedupKey = normalizeDedupKey(rawNumber)
                    if (dedupKey.isEmpty() || !seenNumbers.add(dedupKey)) continue
                    val name = (if (nameCol >= 0) cursor.getString(nameCol) else null)
                        ?.takeIf { it.isNotBlank() } ?: rawNumber
                    results.add(ClusterContactEntry(name = name, number = rawNumber))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed querying starred contacts: ${e.message}")
        }
        cachedFavs.set(results)
        return results
    }

    @SuppressLint("Range")
    fun queryRecentCalls(maxCount: Int = MAX_CLUSTER_ENTRIES): List<ClusterRecentCallEntry> {
        if (!hasReadCallLogPermission()) return emptyList()
        val results = ArrayList<ClusterRecentCallEntry>(maxCount)
        val missedOnly = ArrayList<ClusterRecentCallEntry>(maxCount)
        val seenAll = HashSet<String>()
        val seenMissed = HashSet<String>()
        val projection = arrayOf(
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE
        )
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val numCol = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeCol = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val lookupCache = HashMap<String, String?>()
                var scannedRows = 0
                var lookupsUsed = 0
                while (cursor.moveToNext() &&
                    scannedRows < MAX_CALLLOG_ROWS_TO_SCAN &&
                    (results.size < maxCount || missedOnly.size < maxCount)
                ) {
                    scannedRows++
                    val rawNumber = if (numCol >= 0) cursor.getString(numCol).orEmpty().trim() else ""
                    val dedupKey = normalizeDedupKey(rawNumber)
                    if (dedupKey.isEmpty()) continue
                    val callType = if (typeCol >= 0) cursor.getInt(typeCol) else 0
                    val status = mapCallType(callType)
                    val needsAll = results.size < maxCount && !seenAll.contains(dedupKey)
                    val needsMissed = status == RecentCallStatus.MISSED && missedOnly.size < maxCount && !seenMissed.contains(dedupKey)
                    if (!needsAll && !needsMissed) continue

                    val cachedName = if (nameCol >= 0) cursor.getString(nameCol) else null
                    val resolvedName = cachedName?.takeIf { it.isNotBlank() }
                        ?: lookupCache.getOrElse(dedupKey) {
                            val fetched = if (lookupsUsed < MAX_CONTACT_LOOKUPS) {
                                lookupsUsed++
                                resolveContactNameOrNull(rawNumber)
                            } else {
                                null
                            }
                            lookupCache[dedupKey] = fetched
                            fetched
                        }
                        ?: rawNumber

                    if (needsAll && seenAll.add(dedupKey)) {
                        results.add(ClusterRecentCallEntry(resolvedName, rawNumber, status))
                    }
                    if (needsMissed && seenMissed.add(dedupKey)) {
                        missedOnly.add(ClusterRecentCallEntry(resolvedName, rawNumber, status))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed querying call log: ${e.message}")
        }
        cachedRecents.set(results)
        cachedMissed.set(missedOnly)
        return results
    }

    fun getLatestMissedCall(): ClusterRecentCallEntry? {
        val current = cachedMissed.get()
        if (current.isNotEmpty()) return current.first()
        queryRecentCalls()
        return cachedMissed.get().firstOrNull()
    }

    fun resolveDialTarget(dialSource: Int, dialIndex: Int): Pair<String, String>? {
        if (dialIndex !in 0 until MAX_CLUSTER_ENTRIES) return null
        return when (dialSource) {
            DIAL_SOURCE_FAVOURITES -> {
                val list = cachedFavs.get().ifEmpty { queryFavoriteContacts() }
                list.getOrNull(dialIndex)?.let { it.name to it.number }
            }
            DIAL_SOURCE_RECENTS -> {
                val list = cachedRecents.get().ifEmpty { queryRecentCalls() }
                list.getOrNull(dialIndex)?.let { it.name to it.number }
            }
            DIAL_SOURCE_MISSED -> {
                val list = cachedMissed.get().ifEmpty {
                    queryRecentCalls()
                    cachedMissed.get()
                }
                list.getOrNull(dialIndex)?.let { it.name to it.number }
            }
            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    fun placeClusterCall(displayName: String, rawNumber: String): Boolean {
        val cleanNumber = rawNumber.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (cleanNumber.isBlank()) {
            Log.w(TAG, "Refusing cluster dialback: empty phone number")
            return false
        }
        val maskedName = DebugLogRepository.maskPersonName(displayName)
        val maskedNum = DebugLogRepository.maskPhoneNumber(cleanNumber)
        if (!hasCallPhonePermission()) {
            Log.w(TAG, "CALL_PHONE permission missing; cannot place cluster call to $maskedName")
            DebugLogRepository.error("Dialback", "CALL_PHONE permission missing for $maskedName")
            return false
        }
        val uri = Uri.fromParts("tel", cleanNumber, null)
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecom != null) {
                telecom.placeCall(uri, Bundle())
                Log.i(TAG, "Placed cluster call via TelecomManager -> $maskedName ($maskedNum)")
                DebugLogRepository.telephony(
                    "Dialback",
                    "Placed cluster call -> $maskedName ($maskedNum)"
                )
                true
            } else {
                startCallIntent(uri, displayName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager.placeCall fallback to ACTION_CALL: ${e.message}")
            startCallIntent(uri, displayName)
        }
    }

    private fun startCallIntent(uri: Uri, displayName: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Started ACTION_CALL for $displayName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed placing cluster call: ${e.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    fun sendRejectSms(rawNumber: String?): Boolean {
        val settings = AppSettings.getInstance(context)
        if (!settings.isSmsRejectEnabled) {
            Log.i(TAG, "Reject-with-SMS disabled in settings; skipping auto-reply")
            return false
        }
        val cleanNumber = rawNumber?.filter { it.isDigit() || it == '+' }.orEmpty()
        if (cleanNumber.isBlank()) {
            Log.w(TAG, "Reject-with-SMS skipped: caller number unavailable")
            return false
        }
        if (!hasSendSmsPermission()) {
            Log.w(TAG, "SEND_SMS permission missing; cannot send reject auto-reply SMS")
            DebugLogRepository.error("SMS", "SEND_SMS permission missing; reject SMS skipped")
            return false
        }
        val now = SystemClock.elapsedRealtime()
        synchronized(smsLock) {
            if (isSendingSms) return false
            if (lastSmsSentElapsedMs > 0L && (now - lastSmsSentElapsedMs) < SMS_DEBOUNCE_MS) {
                Log.i(TAG, "Reject-with-SMS debounced within ${SMS_DEBOUNCE_MS}ms window")
                return false
            }
            isSendingSms = true
        }

        val message = settings.smsRejectMessage.trim().ifBlank { AppSettings.DEFAULT_REJECT_SMS_TEMPLATE }
        return try {
            val smsManager = resolveDefaultSmsManager()
            if (smsManager == null) {
                Log.w(TAG, "SmsManager unavailable on device")
                DebugLogRepository.error("SMS", "SmsManager unavailable on device")
                return false
            }
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(cleanNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(cleanNumber, null, message, null, null)
            }
            synchronized(smsLock) {
                lastSmsSentElapsedMs = SystemClock.elapsedRealtime()
            }
            val masked = DebugLogRepository.maskPhoneNumber(cleanNumber)
            Log.i(TAG, "Auto-reply SMS sent to $masked")
            DebugLogRepository.telephony("SMS", "Reject auto-reply SMS sent to $masked")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending reject auto-reply SMS: ${e.message}")
            DebugLogRepository.error("SMS", "Reject SMS failed: ${e.message}")
            false
        } finally {
            synchronized(smsLock) {
                isSendingSms = false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveDefaultSmsManager(): SmsManager? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            val defaultSubId = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (base != null && defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return base.createForSubscriptionId(defaultSubId)
            }
            return base
        }
        val defaultSubId = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return SmsManager.getSmsManagerForSubscriptionId(defaultSubId)
        }
        return SmsManager.getDefault()
    }

    private fun mapCallType(callType: Int): RecentCallStatus {
        return when (callType) {
            CallLog.Calls.INCOMING_TYPE,
            CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> RecentCallStatus.INCOMING
            CallLog.Calls.OUTGOING_TYPE -> RecentCallStatus.OUTGOING
            CallLog.Calls.MISSED_TYPE,
            CallLog.Calls.REJECTED_TYPE -> RecentCallStatus.MISSED
            else -> RecentCallStatus.UNKNOWN
        }
    }

    private fun normalizeDedupKey(raw: String): String {
        val digits = OtcPacketBuilder.normalizeClusterDigits(raw)
        return digits.ifEmpty { raw.trim() }
    }

    private fun resolveContactNameOrNull(phoneNumber: String): String? {
        if (!hasReadContactsPermission() || phoneNumber.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c ->
                val idx = c.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}

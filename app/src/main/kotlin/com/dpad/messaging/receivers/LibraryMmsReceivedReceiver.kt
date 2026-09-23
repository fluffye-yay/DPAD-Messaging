package com.dpad.messaging.receivers

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.dpad.messaging.App
import com.dpad.messaging.BuildConfig
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.AppCoroutineScopes
import com.dpad.messaging.helpers.MmsHelper
import com.dpad.messaging.helpers.NotificationHelper
import com.dpad.messaging.helpers.SmsWhitelistManager
import com.klinker.android.send_message.MmsReceivedReceiver
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus

/**
 * Handles MMS download-complete callbacks from Klinker DownloadManager.
 *
 * This is the concrete receiver required by BroadcastUtils routing
 * (taskAffinity == MmsReceivedReceiver.MMS_RECEIVED).
 */
class LibraryMmsReceivedReceiver : MmsReceivedReceiver() {

    override fun onMessageReceived(context: Context, messageUri: Uri) {
        val pendingResult = goAsync()
        AppCoroutineScopes.io.launch {
            try {
                processMessageReceived(context, messageUri)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processMessageReceived(context: Context, messageUri: Uri) {
        val msgId = messageUri.lastPathSegment?.toLongOrNull() ?: run {
            if (BuildConfig.DEBUG) Log.w(TAG, "LibraryMmsReceivedReceiver: invalid messageUri=$messageUri")
            EventBus.getDefault().post(RefreshConversations())
            return
        }

        processMmsRow(context, msgId)
    }

    override fun onError(context: Context, error: String) {
        Log.e(TAG, "LibraryMmsReceivedReceiver error: $error")
        // On some ROMs the system MmsService persists the downloaded MMS into the
        // provider itself instead of writing klinker's temp cache file, so the
        // file-path handoff above fails. Fall back to scanning the provider for
        // the freshly-arrived inbox MMS so the notification/refresh still fires.
        AppCoroutineScopes.io.launch {
            try {
                val fallback = findNewestInboxMms(context)
                if (fallback > 0L) {
                    processMmsRow(context, fallback)
                } else {
                    EventBus.getDefault().post(RefreshConversations())
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "LibraryMmsReceivedReceiver fallback failed: ${e.message}")
                EventBus.getDefault().post(RefreshConversations())
            }
        }
    }

    private suspend fun processMmsRow(context: Context, msgId: Long) {
        val (threadId, subject) = queryThreadAndSubject(context, msgId)
        val from = getMmsFromAddress(context, msgId)

        val filterResult = SmsWhitelistManager.check(context, from)
        if (!filterResult.allowed) {
            Log.i(TAG, "LibraryMmsReceivedReceiver: dropped MMS from $from - ${filterResult.reason}")
            runCatching { context.contentResolver.delete("content://mms/$msgId".toUri(), null, null) }
            EventBus.getDefault().post(RefreshConversations())
            return
        }

        val body = MmsHelper.getMmsDisplayBody(context, msgId, subject)

        val blockedNumbers = App.get().database.blockedNumbersDao().getAll()
        val blockedKeywords = App.get().database.blockedKeywordsDao().getAll()

        val fromDigits = from.filter { it.isDigit() }
        val isBlockedByNumber = blockedNumbers.any { bn ->
            val digits = bn.number.filter { it.isDigit() }
            bn.number == from || digits == fromDigits
        }
        val isBlockedByKeyword = blockedKeywords.any { kw ->
            body.contains(kw.keyword, ignoreCase = true)
        }

        if (!isBlockedByNumber && !isBlockedByKeyword && from.isNotBlank()) {
            val senderName = App.get().contactHelper.getDisplayName(from)
            NotificationHelper.showIncomingNotification(context, threadId, senderName, from, body)
        }

        EventBus.getDefault().post(RefreshConversations())
        if (threadId > 0L) {
            EventBus.getDefault().post(RefreshMessages(threadId))
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "LibraryMmsReceivedReceiver: processed msgId=$msgId threadId=$threadId from=$from")
        }
    }

    /**
     * Finds the most recent inbox (msg_box=1) MMS row that arrived within the
     * last few minutes and is backed by a real thread. Used only as a fallback
     * when the download-complete callback can't read klinker's temp file, since
     * the system already persisted it. Placeholder notification-ind rows use
     * DUMMY_THREAD_ID (Long.MAX_VALUE), so those are excluded.
     */
    private suspend fun findNewestInboxMms(context: Context): Long {
        val cutoffSecs = (System.currentTimeMillis() - 180_000L) / 1000L
        return runCatching {
            context.contentResolver.query(
                "content://mms".toUri(),
                arrayOf("_id"),
                "msg_box = 1 AND date > ? AND thread_id > 0",
                arrayOf(cutoffSecs.toString()),
                "date DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    if (recentlyProcessed(id)) -1L else id
                } else -1L
            } ?: -1L
        }.getOrDefault(-1L)
    }

    /**
     * Guards against onError firing twice for the same download: both the
     * klinker rebroadcast and this fallback can race to the same fresh row.
     */
    private fun recentlyProcessed(msgId: Long): Boolean {
        val now = System.currentTimeMillis()
        if (lastProcessedId == msgId && now - lastProcessedAt < 30_000L) return true
        lastProcessedId = msgId
        lastProcessedAt = now
        return false
    }

    private fun queryThreadAndSubject(context: Context, msgId: Long): Pair<Long, String> {
        return runCatching {
            context.contentResolver.query(
                "content://mms/$msgId".toUri(),
                arrayOf("thread_id", "sub"),
                null,
                null,
                null
            )?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    val threadId = cursor.getLong(0)
                    val subject = cursor.getString(1).orEmpty()
                    return@runCatching threadId to subject
                }
                -1L to ""
            } ?: (-1L to "")
        }.getOrDefault(-1L to "")
    }

    private fun getMmsFromAddress(context: Context, msgId: Long): String {
        return runCatching {
            context.contentResolver.query(
                "content://mms/$msgId/addr".toUri(),
                arrayOf("address"),
                "type = ?",
                arrayOf("137"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val addr = cursor.getString(0).orEmpty()
                    if (addr.isNotBlank() && addr != "insert-address-token") {
                        return@runCatching addr
                    }
                }
                ""
            } ?: ""
        }.getOrDefault("")
    }

    companion object {
        private const val TAG = "DPAD_MSG"
        private var lastProcessedId = -1L
        private var lastProcessedAt = 0L
    }
}

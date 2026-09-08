package org.fossify.messages.helpers

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.Closeable
import java.util.Timer
import java.util.TimerTask
import org.fossify.messages.R

/**
 * The one progress sender both automation doors use — the [org.fossify.messages.receivers.StateExportReceiver]
 * broadcasts and the [org.fossify.messages.automation.AutomationProvider] data door alike.
 *
 * It is one implementation on purpose. Both callers treat a progress broadcast as a **heartbeat** and
 * presume an app silent for two minutes to be dead, so this is a watchdog — and two implementations of
 * the same watchdog drift, with the one that drifts always being the one nobody is looking at. What
 * differs between the doors is only the correlation id: the receiver answers a caller's "reply_id",
 * the data door its own "job_id", which it sends in *both* extras so a single reader on the caller's
 * side serves either.
 *
 * Real counts, never a percentage; at most one broadcast per [PROGRESS_THROTTLE_MS].
 *
 * ## A throttle is not a heartbeat
 *
 * A throttle only ever *withholds* messages, so an export that stops reporting stops broadcasting —
 * and "our export is fast" does not save us on the data door. There the destination is a descriptor
 * **the caller opened, which may be a pipe**: one write then blocks for exactly as long as 応用管理 is
 * slow to drain it, and this app's messages category carries every MMS attachment through it. The
 * export core reports a line before each category and per message inside one, so the last line is
 * always current when a write blocks — and [Channel] re-sends it every [HEARTBEAT_MS] until the
 * numbers move again. Nothing is invented: a heartbeat repeats the truth rather than fabricating
 * progress, which is what makes it honest to hold a caller's slot with.
 *
 * ## The beat starts with the job, not with the first count
 *
 * That "the core always reports before the first write that could block" is true of the **export**
 * and was false of the **import**, which is the direction that broke. An import reads the caller's
 * whole archive, unzips it and parses the corpus before it can count anything, and the heartbeat
 * used to stay silent until something had been counted — so the longest stretch of the longest
 * operation was the one stretch with no liveness signal at all. 応用管理 heard nothing whatsoever
 * from a メッセージ import and failed it as dead after ten minutes (2026-09-08).
 *
 * So a [Channel] speaks once the moment it is opened, and keeps beating from there. The opening
 * line carries [UNKNOWN] rather than a count: `current = -1` is the contract's own liveness value,
 * which the caller already refuses to let move its high-water mark. Still nothing invented — "I
 * have started" is a fact, and it is the one fact the caller was missing.
 *
 * A caller that passed no progress action gets nothing, so every part of this is additive.
 */
object AutomationProgress {

    /**
     * §3 gives up on an app silent for 30 s. Beating at two thirds of that leaves room for one lost or
     * delayed broadcast before the caller starts counting us out.
     */
    private const val HEARTBEAT_MS = 20_000L

    private const val TAG = "MessejiAutomation"

    /**
     * The count that means "no count" — a liveness beat rather than a measurement.
     *
     * The caller reads it as one: it neither renders a `-1` nor lets it move the high-water mark it
     * uses to detect a restart. Anything that genuinely cannot be counted yet says this instead of
     * guessing a number.
     */
    const val UNKNOWN = -1L

    fun channel(
        context: Context,
        progressAction: String,
        replyPackage: String,
        replyId: String,
        jobId: String? = null,
    ): Channel {
        val appContext = context.applicationContext
        return Channel(
            context = appContext,
            progressAction = progressAction,
            replyPackage = replyPackage,
            replyId = replyId,
            jobId = jobId,
            appLabel = appContext.getString(R.string.app_launcher_name),
            unitCategory = appContext.getString(R.string.state_progress_unit_category),
            openingText = appContext.getString(R.string.state_progress_starting),
        )
    }

    /**
     * The throttled progress channel, the unthrottled completion broadcast, and the heartbeat behind
     * both. **[close] it in a `finally`** — the timer thread is a daemon, so a leaked one cannot hold
     * the process up, but it would go on broadcasting a finished export's last line.
     */
    // A broadcast that cannot be sent must never take the export down with it: whatever the platform
    // throws here becomes a log line, not a failed backup.
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    class Channel internal constructor(
        private val context: Context,
        private val progressAction: String,
        private val replyPackage: String,
        private val replyId: String,
        private val jobId: String?,
        private val appLabel: String,
        private val unitCategory: String,
        private val openingText: String,
    ) : Closeable {

        private class Line(val current: Long, val total: Long, val unit: String, val text: String)

        // Written from the export thread, read from the heartbeat's.
        @Volatile
        private var lastSentAt = 0L

        // Opened with the "starting" line already in it, so the heartbeat below has something true
        // to repeat from its very first tick — see the class comment on why silence here was fatal.
        @Volatile
        private var lastLine: Line? = Line(UNKNOWN, UNKNOWN, unitCategory, openingText)

        private val heartbeat: Timer? = if (progressAction.isEmpty()) {
            null
        } else {
            Timer("automation-progress-heartbeat", true).apply {
                schedule(
                    object : TimerTask() {
                        override fun run() = beat()
                    },
                    HEARTBEAT_MS,
                    HEARTBEAT_MS,
                )
            }
        }

        init {
            // Speak immediately, before any work starts. Waiting for the first tick would leave a
            // 20-second hole at the front of every job, and an import that blocks in its opening
            // read — the caller's descriptor, on storage several sister apps are hammering during a
            // batch restore — never reaches a tick at all. One broadcast is a small price for the
            // caller being able to tell "working" from "dead" from the first instant.
            if (progressAction.isNotEmpty()) {
                send(UNKNOWN, UNKNOWN, unitCategory, openingText)
            }
        }

        /** What the export core reports into. Throttled; the heartbeat covers what it withholds. */
        val reporter: ProgressReporter = { current, total, unit, text ->
            lastLine = Line(current, total, unit, text)
            if (progressAction.isNotEmpty() && System.currentTimeMillis() - lastSentAt >= PROGRESS_THROTTLE_MS) {
                send(current, total, unit, text)
            }
        }

        /** The mandatory final message, unthrottled — [categories] of [categories], done. */
        fun complete(categories: Long) {
            if (progressAction.isNotEmpty()) {
                send(categories, categories, unitCategory, "$unitCategory $categories/$categories")
            }
        }

        override fun close() {
            heartbeat?.cancel()
        }

        /**
         * Repeat the last line if the real reporter has genuinely gone quiet.
         *
         * There is always a line to repeat now — the channel opens with one — so this beats for the
         * whole life of a job rather than only after the first count. The null guard stays as a
         * guard, not as the silence it used to be.
         */
        private fun beat() {
            val line = lastLine ?: return
            if (System.currentTimeMillis() - lastSentAt < HEARTBEAT_MS) {
                return
            }
            Log.i(TAG, "heartbeat → ${line.text}")
            send(line.current, line.total, line.unit, line.text)
        }

        private fun send(current: Long, total: Long, unit: String, text: String) {
            lastSentAt = System.currentTimeMillis()
            try {
                val intent = Intent(progressAction)
                    .setPackage(replyPackage.ifEmpty { null })
                    .putExtra(EXTRA_REPLY_ID, replyId)
                    .putExtra(EXTRA_PROGRESS_APP, appLabel)
                    .putExtra(EXTRA_PROGRESS_TEXT, text)
                    .putExtra(EXTRA_PROGRESS_CURRENT, current)
                    .putExtra(EXTRA_PROGRESS_TOTAL, total)
                    .putExtra(EXTRA_PROGRESS_UNIT, unit)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                if (jobId != null) {
                    intent.putExtra(EXTRA_JOB_ID, jobId)
                }
                // The contract's "result" is a LABEL the caller prints BEFORE the counts it formats
                // itself from current/total/unit — it is not the whole line. Putting our own
                // "Messages 0/1443" there made 応用管理 render "Messages 0/1443 0/1,443 Messages"
                // (白い熊, 2026-09-08, and quite right). So it carries words only when there are no
                // numbers for them to collide with: a beat, where the caller has nothing else to
                // show and would otherwise print nothing at all.
                if (current < 0) {
                    intent.putExtra(EXTRA_REPLY_RESULT, text)
                }
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                Log.w(TAG, "progress broadcast failed: $e")
            }
        }
    }
}

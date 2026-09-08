package org.fossify.messages.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isUpsideDownCakePlus
import org.fossify.messages.R
import org.fossify.messages.helpers.AutomationProgress
import org.fossify.messages.helpers.EXTRA_JOB_ID
import org.fossify.messages.helpers.EXTRA_REPLY_ID
import org.fossify.messages.helpers.EXTRA_REPLY_RESULT
import org.fossify.messages.helpers.SettingsEximport

/**
 * Where a data export or import started at [AutomationProvider] actually runs.
 *
 * ## Why a foreground service and not the provider call
 *
 * The call returns in milliseconds; this can run for minutes on a phone with years of messages. Two
 * hard reasons it cannot be done anywhere cheaper:
 *
 * - **A binder call holds the caller.** 応用管理 is drawing a list; a multi-minute synchronous call
 *   would freeze its UI, report no progress and refuse cancellation.
 * - **A backgrounded app writing for minutes is frozen mid-stream on 白い熊's EMUI**, which yields a
 *   truncated archive underneath a success reply — the worst possible failure, because it is
 *   indistinguishable from a good backup until the day it is restored.
 *
 * ## The descriptor has exactly one owner, from the moment it arrives
 *
 * [AutomationProvider] duplicated it before it got here, because the original belongs to the binder
 * transaction and is closed the moment `call()` returns. This service takes its copy **out of
 * [HANDOVER] before anything that can throw** — `startForeground` can — and closes it in a `finally`.
 * A descriptor stranded in that map holds the caller's file open for the life of the process, and a
 * caller can neither checksum nor encrypt a file that is still open.
 *
 * Exactly one terminal reply per job, guarded by an [AtomicBoolean] so a synchronous failure and an
 * asynchronous success can never both fire — the same guard the broadcast contract has carried since
 * the first sister app.
 */
// Broad catches are deliberate: whatever the export core, a caller's descriptor or the platform's
// foreground-service rules throw must become one ERROR: line on the reply broadcast, never a crash in
// the app being backed up.
@Suppress("TooGenericExceptionCaught")
class AutomationDataService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Three steps, in this order, because two of the rules that govern this method collide if either
     * is applied on its own.
     *
     * 1. **Read the extras.** Microseconds and no early returns, so the five-second `startForeground`
     *    window is untouched — and a refusal in step 2 has a reply address to answer at.
     * 2. **Go foreground, guarded.** By the time this runs the caller has ALREADY been handed
     *    `OK:<job_id>`, because `startForegroundService` succeeded; a refusal here must therefore be
     *    answered with the terminal broadcast, not merely caught.
     * 3. **Then the early returns** — a stale job id, a descriptor already consumed. These come last
     *    because a service started with `startForegroundService` must reach `startForeground`
     *    whatever it then decides, or the platform kills the app for it.
     *
     * The obvious orderings each break one of those. Extras first, then the guard, then the bail.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // STEP 1 — the extras, including the address a refusal answers at.
        val jobId = intent?.getStringExtra(EXTRA_JOB)
        // Out of the map here, before anything that can fail: from this point the descriptor has
        // exactly one owner, and no failure below can leave the caller's file parked in a map
        // nothing will ever read again.
        val fd = jobId?.let { HANDOVER.remove(it) }
        val importing = intent?.getBooleanExtra(EXTRA_IMPORTING, false) == true
        val replyAction = intent?.getStringExtra(AutomationProvider.KEY_REPLY_ACTION)?.trim().orEmpty()
        val replyPackage = intent?.getStringExtra(AutomationProvider.KEY_REPLY_PACKAGE)?.trim().orEmpty()
        val progressAction = intent?.getStringExtra(AutomationProvider.KEY_PROGRESS_ACTION)?.trim().orEmpty()
        val items = intent?.getStringExtra(AutomationProvider.KEY_ITEMS)
        val replied = AtomicBoolean(false)

        fun reply(result: String) {
            if (jobId == null || !replied.compareAndSet(false, true)) {
                return
            }
            AutomationJobs.finish(jobId)
            Log.i(TAG, "job $jobId → $result")
            if (replyAction.isEmpty()) {
                return
            }
            try {
                sendBroadcast(
                    Intent(replyAction)
                        .setPackage(replyPackage.ifEmpty { null })
                        // without this a backgrounded caller never hears the answer — and on a clean
                        // phone the caller may not have been launched at all
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        .putExtra(EXTRA_JOB_ID, jobId)
                        .putExtra(EXTRA_REPLY_ID, jobId)
                        .putExtra(EXTRA_REPLY_RESULT, result)
                )
            } catch (e: Exception) {
                Log.w(TAG, "reply broadcast failed: $e")
            }
        }

        // STEP 2 — the promotion, guarded. Catching without replying is not a fix: it turns a crash
        // into a silent no-export, and a caller that waits out its whole timeout cannot tell that
        // apart from an app that never implemented the contract.
        try {
            goForeground(importing)
        } catch (e: Exception) {
            Log.w(TAG, "could not go foreground: $e")
            reply(startRefusal(this, e))
            runCatching { fd?.close() }
            return stop(startId)
        }

        // STEP 3 — only now the early returns.
        if (jobId == null || fd == null) {
            // START_NOT_STICKY, so this is only ever a restart we have no descriptor for
            Log.w(TAG, "no job to run (id=$jobId)")
            return stop(startId)
        }

        ensureBackgroundThread {
            // The job id is the correlation id on this door, sent in both extras so one progress
            // reader on the caller's side serves this door and the receiver's alike.
            //
            // Opened INSIDE the try. It used to sit above it, where anything it threw killed this
            // thread with the reply address in scope and unused — no reply, no `finally`, and a
            // caller left waiting out its whole timeout. Nothing on the way to a reply may sit
            // outside the guard.
            var progress: AutomationProgress.Channel? = null
            try {
                progress = AutomationProgress.channel(this, progressAction, replyPackage, jobId, jobId)
                if (importing) {
                    runImport(fd, progress, ::reply)
                } else {
                    runExport(jobId, fd, items, progress, ::reply)
                }
            } catch (t: Throwable) {
                // Throwable, not Exception. An import holds the whole archive AND the parsed corpus
                // in memory at once, so OutOfMemoryError is a real outcome here — and an Error is
                // not an Exception, so it used to slip past this catch and be answered with
                // silence. Whatever goes wrong, the caller gets a line about it.
                reply("ERROR:${reason(t)}")
            } finally {
                // stops the heartbeat before the reply is anyone's concern
                progress?.close()
                runCatching { fd.close() }
                stop(startId)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Write the whole backup into the caller's descriptor — one ZIP, exactly as the Export/Import page
     * and the broadcast contract write, so an archive is an archive whichever door produced it.
     *
     * The bytes are counted as they go rather than stat'ed afterwards: the file belongs to the caller
     * and we may not be able to see it at all — it can be a pipe, or a descriptor into a directory
     * this app cannot list.
     */
    private fun runExport(
        jobId: String,
        fd: ParcelFileDescriptor,
        items: String?,
        progress: AutomationProgress.Channel,
        reply: (String) -> Unit,
    ) {
        val cats = SettingsEximport.categoriesFor(items)
        if (cats == null) {
            reply("ERROR:unknown category in items: $items")
            return
        }

        var written = 0L
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
                val counting = object : OutputStream() {
                    override fun write(b: Int) {
                        out.write(b)
                        written++
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        out.write(b, off, len)
                        written += len
                    }
                }
                SettingsEximport.export(
                    context = this,
                    categories = cats,
                    out = counting,
                    onProgress = progress.reporter,
                ) { AutomationJobs.isCancelled(jobId) }
            }
        } catch (e: SettingsEximport.ExportCancelledException) {
            // Nothing to delete: the destination is the caller's file, and 応用管理 discards a job it
            // cancelled. Saying so is the whole of our side.
            Log.i(TAG, "export cancelled ($e)")
            reply("ERROR:cancelled")
            return
        }

        progress.complete(cats.size.toLong())
        reply("OK:$written|${cats.size} categories")
    }

    /**
     * Spool the caller's archive to a cache file, then apply it from there.
     *
     * **To disk, not into a byte array.** Pulling the whole thing into memory to sniff it is fine for
     * a settings ZIP and wrong for this app: an archive of 白い熊's message corpus carries every MMS
     * attachment in it. The guarantee is unchanged — nothing is written until the whole archive has
     * arrived and unzipped cleanly — only the bound moves from RAM to disk.
     *
     * An empty summary means the file carried none of our categories, i.e. it is not one of our
     * exports; that is reported rather than answered as a success over nothing.
     */
    private fun runImport(
        fd: ParcelFileDescriptor,
        progress: AutomationProgress.Channel,
        reply: (String) -> Unit,
    ) {
        val spool = File(cacheDir, "automation-import-${System.currentTimeMillis()}.zip")
        try {
            spoolTo(spool, fd, progress)
            if (spool.length() == 0L) {
                reply("ERROR:empty archive")
                return
            }

            // Every category we know: import merges per key and skips the ones the archive lacks, so
            // this restores exactly what is in the file and nothing else.
            val outcome = spool.inputStream().use {
                SettingsEximport.import(
                    context = this,
                    input = it,
                    categories = SettingsEximport.Category.entries.toSet(),
                    onProgress = progress.reporter,
                )
            }
            val restored = outcome.summary.lineSequence().filter { it.isNotBlank() }.toList()
            if (restored.isEmpty() && outcome.refused.isEmpty()) {
                reply("ERROR:archive carries no categories")
                return
            }
            Log.i(TAG, "imported: ${restored.joinToString(" · ")}")
            if (outcome.refused.isNotEmpty()) {
                // A reserved key, like ERROR:no-foreground-start beside it: the caller can offer
                // 白い熊 the one action that fixes it instead of printing a sentence at them. The
                // rest of the archive HAS been applied and re-running is idempotent — the writers
                // skip what is already there — so this is "come back to it", not "start over".
                //
                // The role is logged beside it because the two can disagree: the refusal is decided
                // by writes that did not land, and a phone that says it holds the role while
                // dropping every write is a different problem from one that plainly does not hold
                // it. Whoever reads this log next should not have to guess which they had.
                val refusal = SettingsEximport.writeRefusal(this)
                Log.w(TAG, "refused: ${outcome.refused.joinToString(" · ")} — ${refusal.key}")
                // The whole diagnosis travels in the reply, because that is the one place 白い熊
                // actually reads it: 応用管理 prints this line verbatim in its operation log. A bare
                // key sent them to a Settings screen that disagreed with it (2026-09-08).
                reply("${refusal.key} — ${SettingsEximport.refusalAdvice(this, refusal)}")
                return
            }
            // 応用管理 force-stops us straight after this, deliberately and on its side: a running
            // process writes its cached SharedPreferences back out at orderly shutdown and would
            // silently undo the import that just happened.
            reply("OK:${restored.size} categories restored")
        } finally {
            spool.delete()
        }
    }

    /**
     * Copy the caller's archive to [spool], counting the bytes out loud.
     *
     * **The phase that used to say nothing, and the one that can block longest.** The bytes come
     * from a descriptor the caller owns, on storage that during a batch restore several sister apps
     * are reading and writing at once; nothing downstream reports until the whole archive has been
     * spooled, unzipped and parsed. So an import used to be silent for its entire first half, and a
     * caller cannot tell a slow app from a dead one when both say the same nothing.
     *
     * The total is `statSize` where the descriptor can answer and [AutomationProgress.UNKNOWN] where
     * it cannot — a pipe has no length, and an invented total is worse than an honest unknown.
     */
    private fun spoolTo(spool: File, fd: ParcelFileDescriptor, progress: AutomationProgress.Channel) {
        val total = fd.statSize.takeIf { it > 0L } ?: AutomationProgress.UNKNOWN
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            spool.outputStream().use { out -> copyCounting(input, out, total, progress) }
        }
    }

    /** [copyTo] with a running count, because the count is the whole point of doing it by hand. */
    private fun copyCounting(
        input: InputStream,
        out: OutputStream,
        total: Long,
        progress: AutomationProgress.Channel,
    ) {
        val unit = getString(R.string.state_progress_unit_archive)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            out.write(buffer, 0, read)
            copied += read
            // Throttled inside the channel, so one call per buffer costs nothing. No denominator
            // when the descriptor would not give us one — "12345" beats "12345/-1".
            val line = if (total > 0L) "$unit $copied/$total" else "$unit $copied"
            progress.reporter(copied, total, unit, line)
        }
    }

    /**
     * The typed `startForeground` is API 34; below it the manifest's `foregroundServiceType` is what
     * counts. This is an **API-availability** question, which is the only kind a version check answers
     * correctly on this phone: 白い熊's EMUI reports `SDK_INT = 31` on a platform based on Android 13,
     * so anything gated on the version as a statement about *behaviour* would be wrong in both
     * directions. Either branch may still throw, which the caller treats as the refusal it is.
     */
    private fun goForeground(importing: Boolean) {
        val notification = notification(importing)
        if (isUpsideDownCakePlus()) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun reason(e: Throwable): String =
        (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName).replace('\n', ' ')

    private fun notification(importing: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channelName = getString(R.string.automation_data_channel)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL, channelName, NotificationManager.IMPORTANCE_LOW)
        )
        val title = getString(if (importing) R.string.automation_data_importing else R.string.automation_data_exporting)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_messenger)
            .setOngoing(true)
            .build()
    }

    private fun stop(startId: Int): Int {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        const val TAG = "MessejiAutomation"
        private const val CHANNEL = "automation_data"
        private const val NOTIFICATION_ID = 9714
        private const val EXTRA_JOB = "job"
        private const val EXTRA_IMPORTING = "importing"

        /**
         * The descriptor's way across, because an Intent is the wrong vehicle for one.
         *
         * A [ParcelFileDescriptor] in an Intent extra is duplicated by the system on delivery and the
         * copy's lifetime stops being ours to reason about. Handing it through a map keyed by the job
         * id keeps exactly one open descriptor with exactly one owner — this service, which takes it
         * out before anything that can throw and closes it in a `finally`.
         */
        private val HANDOVER = ConcurrentHashMap<String, ParcelFileDescriptor>()

        /**
         * The refusal string for a failed foreground start — classified so the caller only offers
         * 白い熊 a 「電池最適化を除外」 button when that button would actually help.
         *
         * **Two conditions, not one.** The reserved `ERROR:no-foreground-start` key is emitted only
         * when the throwable is the not-allowed exception **and** the exemption is not already held.
         * If it is held and the start was still refused, the cause is something the button cannot
         * touch — アプリ起動管理 on 自動管理 being the likeliest on this phone — and offering it
         * sends 白い熊 to a setting that is already correct. "Is this the fault the button fixes"
         * and "is the button still available" are different questions; only the pair answers it.
         *
         * **Matched by NAME, never `instanceof`.** `ForegroundServiceStartNotAllowedException` is
         * API 31 and this app's minSdk is 26, so a class literal in a catch block — the natural way
         * to write this — would fail to load on an older device.
         */
        fun startRefusal(context: Context, t: Throwable): String {
            val notAllowed = t.javaClass.simpleName == "ForegroundServiceStartNotAllowedException"
            val exempt = runCatching {
                context.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName)
            }.getOrDefault(false)
            return if (notAllowed && !exempt) {
                "ERROR:no-foreground-start"
            } else {
                "ERROR:cannot start export service: ${t.javaClass.simpleName}"
            }
        }

        fun start(
            context: Context,
            jobId: String,
            fd: ParcelFileDescriptor,
            importing: Boolean,
            extras: Bundle?,
        ) {
            HANDOVER[jobId] = fd
            try {
                context.startForegroundService(
                    Intent(context, AutomationDataService::class.java)
                        .putExtra(EXTRA_JOB, jobId)
                        .putExtra(EXTRA_IMPORTING, importing)
                        .putExtra(AutomationProvider.KEY_ITEMS, extras?.getString(AutomationProvider.KEY_ITEMS))
                        .putExtra(
                            AutomationProvider.KEY_REPLY_ACTION,
                            extras?.getString(AutomationProvider.KEY_REPLY_ACTION)
                        )
                        .putExtra(
                            AutomationProvider.KEY_REPLY_PACKAGE,
                            extras?.getString(AutomationProvider.KEY_REPLY_PACKAGE)
                        )
                        .putExtra(
                            AutomationProvider.KEY_PROGRESS_ACTION,
                            extras?.getString(AutomationProvider.KEY_PROGRESS_ACTION)
                        )
                )
            } catch (e: Exception) {
                // the provider closes the descriptor and answers the refusal; don't strand it here
                HANDOVER.remove(jobId)
                throw e
            }
        }
    }
}

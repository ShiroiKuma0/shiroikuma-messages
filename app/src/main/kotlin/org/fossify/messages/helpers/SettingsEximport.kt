package org.fossify.messages.helpers

import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Telephony
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.commons.helpers.ACCENT_COLOR
import org.fossify.commons.helpers.APP_ICON_COLOR
import org.fossify.commons.helpers.BACKGROUND_COLOR
import org.fossify.commons.helpers.COLOR_PICKER_RECENT_COLORS
import org.fossify.commons.helpers.FontHelper
import org.fossify.commons.helpers.IS_SYSTEM_THEME_ENABLED
import org.fossify.commons.helpers.PRIMARY_COLOR
import org.fossify.commons.helpers.TEXT_COLOR
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.helpers.isUpsideDownCakePlus
import org.fossify.messages.R
import org.fossify.messages.models.MessagesBackup
import org.fossify.messages.models.MmsBackup
import org.fossify.messages.models.SmsBackup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Progress as real counts, never a percentage — [current]/[total] of [unit], plus the ready-made
 * [text] line ("メッセージ 1234/8942") that both the in-app panel and the automation contract's
 * progress broadcasts display.
 */
typealias ProgressReporter = (current: Long, total: Long, unit: String, text: String) -> Unit

/**
 * Category export/import of every setting the app can persist (modelled on the Kōjiki fork's
 * exporter). The archive is a ZIP of one JSON file per category plus the imported font files;
 * every category is a slice of the single commons "Prefs" SharedPreferences file, split by key.
 * Prefs are serialized as a typed key→{t,v} map so all types round-trip, and import merges —
 * it never clears — so unrelated or device-local keys survive.
 *
 * [export] is the one export core: the Export/Import page and the headless automation receiver
 * ([org.fossify.messages.receivers.StateExportReceiver]) are two thin callers of it, so a backup
 * written by 自由作業盤 is byte-for-byte the same kind of archive as one written by hand.
 */
@Suppress("TooManyFunctions")
object SettingsEximport {
    private const val FORMAT = "shiroikuma-messeji-export"
    private const val FORMAT_VERSION = 1

    // The family's mandatory backup name: "<english-dash-separated-app-name>_<yyyy-MM-dd_HH-mm-ss>.zip"
    // — no version, no "-export" infix, no suffix, so every sister app's backups sort and read
    // uniformly in 白い熊's one backup directory. Older names ("shiroikuma-messeji-1.9.1-export_…")
    // still start with this prefix, so the "last export" query keeps recognising them.
    const val EXPORT_PREFIX = "shiroikuma-messeji"
    const val EXPORT_SUFFIX = ".zip"
    private const val FONTS_DIR_ENTRY = "fonts/"

    // Device-local prefs file for the export directory (its own file, so it never round-trips
    // through an export of the main "Prefs" file).
    private const val EXIMPORT_PREFS = "eximport"
    private const val KEY_DIR_URI = "dir_uri"

    /**
     * A selectable category; `id` is the JSON file name (`<id>.json`) inside the ZIP and, being
     * stable, is also what the automation contract's "items" extra accepts. [labelRes] is the
     * descriptive label the pickers show (in-app and in 自由作業盤), [shortLabelRes] the bare noun
     * used in progress lines ("区分 3/7 — 設定"). The list is flat: no category has sub-options.
     *
     * [defaultOn] is this app's own answer to whether the item starts ticked — sent as the fourth
     * field of every LIST_CATEGORIES line and used to seed the in-app picker, so both pickers open
     * on the same selection. Everything here is `on`: nothing this app exports is large, derived
     * *and* re-creatable (the case the flag exists for), so the app states the default rather than
     * letting a picker assume one.
     */
    enum class Category(
        val id: String,
        @StringRes val labelRes: Int,
        @StringRes val shortLabelRes: Int,
        val defaultOn: Boolean = true,
    ) {
        MESSAGES("messages", R.string.eim_cat_messages, R.string.eim_cat_messages_short),
        THEME("theme_colors", R.string.eim_cat_theme, R.string.eim_cat_theme_short),
        FONTS("fonts", R.string.eim_cat_fonts, R.string.eim_cat_fonts_short),
        FORMATS("formats", R.string.eim_cat_formats, R.string.eim_cat_formats_short),
        APP_SETTINGS("app_settings", R.string.eim_cat_app_settings, R.string.eim_cat_app_settings_short),
        CONVERSATIONS("conversations", R.string.eim_cat_conversations, R.string.eim_cat_conversations_short),
        BLOCKED("blocked_keywords", R.string.eim_cat_blocked, R.string.eim_cat_blocked_short);

        companion object {
            fun byId(id: String): Category? = entries.firstOrNull { it.id == id }
        }
    }

    // Device-local / runtime keys never worth exporting: migration flags, version counters,
    // storage paths & SAF grants, one-time dialog flags, lock-screen retry state, widget scratch.
    private val EXCLUDED_KEYS = setOf(
        THEME_V1_SEEDED, PURE_YELLOW_MIGRATED, WAS_DB_CLEARED, LAST_RECYCLE_BIN_CHECK,
        SOFT_KEYBOARD_HEIGHT, LAST_BLOCKED_KEYWORD_EXPORT_PATH,
        // the automation gate is device-local: the token must never travel in a backup, and a
        // restored archive must never silently switch this app's automation on — nor, now, silently
        // switch its token requirement off on a phone where 白い熊 had deliberately turned it on
        AUTOMATION_ENABLED, AUTOMATION_REQUIRE_TOKEN, AUTOMATION_TOKEN,
        "app_run_count", "last_version", "app_sideloading_status",
        "sd_card_path_2", "otg_real_path_2", "internal_storage_path", "otg_partition_2",
        "last_handled_shortcut_color", "last_icon_color", "last_export_path",
        "last_exported_settings_folder", "last_exported_settings_file",
        "password_retry_count", "password_count_down_start_ms", "last_unlock_timestamp_ms",
        "initial_widget_height", "widget_id_to_measure",
    )

    private val THEME_KEYS = setOf(
        TEXT_COLOR, BACKGROUND_COLOR, PRIMARY_COLOR, ACCENT_COLOR, APP_ICON_COLOR,
        IS_SYSTEM_THEME_ENABLED, COLOR_PICKER_RECENT_COLORS,
    )

    private val FORMAT_KEYS = setOf(MESSAGE_TIME_FORMAT, USE_IMPERIAL_DATE)

    private val CONVERSATION_KEYS = setOf(PINNED_CONVERSATIONS, CUSTOM_NOTIFICATIONS)

    private val FONT_PREFIXES = listOf(FONT_FAMILY_PREFIX, FONT_WEIGHT_PREFIX, FONT_SIZE_PREFIX)

    /** Which category a prefs key belongs to; null = excluded from export altogether. */
    private fun categoryOf(key: String): Category? = when {
        key in EXCLUDED_KEYS -> null
        key.endsWith("tree_uri_2") -> null
        FONT_PREFIXES.any { key.startsWith(it) } -> Category.FONTS
        key in THEME_KEYS || key.startsWith("theme_") -> Category.THEME
        key in FORMAT_KEYS -> Category.FORMATS
        key in CONVERSATION_KEYS || key.startsWith(USE_SIM_ID_PREFIX) -> Category.CONVERSATIONS
        key == BLOCKED_KEYWORDS -> Category.BLOCKED
        else -> Category.APP_SETTINGS
    }

    fun getDirUri(context: Context): Uri? = context
        .getSharedPreferences(EXIMPORT_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_DIR_URI, null)
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun setDirUri(context: Context, uri: Uri) = context
        .getSharedPreferences(EXIMPORT_PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_DIR_URI, uri.toString()).apply()

    fun exportDir(context: Context): DocumentFile? = getDirUri(context)
        ?.let { runCatching { DocumentFile.fromTreeUri(context, it) }.getOrNull() }
        ?.takeIf { it.isDirectory }

    fun exportFileName(): String = EXPORT_PREFIX + "_" +
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()) + EXPORT_SUFFIX

    /** (message, isWarning) describing the newest export in the configured directory. */
    fun lastExportStatus(context: Context): Pair<String, Boolean> {
        val dir = exportDir(context) ?: return context.getString(R.string.eim_warn_nodir) to true
        val newest = runCatching {
            dir.listFiles().filter {
                it.isFile && it.name?.startsWith(EXPORT_PREFIX) == true &&
                    it.name?.endsWith(EXPORT_SUFFIX) == true
            }.maxByOrNull { it.lastModified() }
        }.getOrNull() ?: return context.getString(R.string.eim_warn_none) to true
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .format(Date(newest.lastModified()))
        return context.getString(R.string.eim_last, timestamp) to false
    }

    /**
     * The categories an automation request means: the ids named in [items], or — absent or empty —
     * this app's DEFAULT set, which is what LIST_CATEGORIES reports as `on` rather than everything
     * the app can write. null when an id names a category we do not export, which the caller answers
     * as "unknown category in items".
     *
     * One resolver for both doors (the broadcast receiver and the provider's data service), so a
     * request cannot mean one thing on one and something else on the other. Every category this app
     * has is [Category.defaultOn], so the default set is currently the whole list.
     */
    fun categoriesFor(items: String?): Set<Category>? {
        val ids = items.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) {
            return Category.entries.filter { it.defaultOn }.toSet()
        }
        val cats = ids.mapNotNull { Category.byId(it) }.toSet()
        return cats.takeIf { it.size == ids.distinct().size }
    }

    /** Thrown out of [export] when [isCancelled] turned true; the caller removes the partial file. */
    class ExportCancelledException : Exception("cancelled")

    private fun throwIfCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) {
            throw ExportCancelledException()
        }
    }

    /**
     * The export core, callable headlessly — no Activity, no user interaction. Writes exactly one ZIP
     * of the selected categories to [out] and reports real counts through [onProgress]: the category
     * being written ("区分 3/7 — 設定"), and inside the messages category (the only slow one — the
     * prefs slices are instant) the messages themselves ("メッセージ 1234/8942"). Unthrottled; a
     * caller that broadcasts progress throttles it.
     *
     * [isCancelled] is polled at every entry boundary (and while the messages are read): the write
     * loop unwinds with an [ExportCancelledException] at the next boundary rather than being killed
     * mid-`write()`.
     */
    fun export(
        context: Context,
        categories: Set<Category>,
        out: OutputStream,
        onProgress: ProgressReporter = { _, _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        // Declaration order, not the caller's, so a ZIP's contents don't depend on how the set was built.
        val ordered = Category.entries.filter { it in categories }
        require(ordered.isNotEmpty()) { "nothing selected" }
        val unit = context.getString(R.string.state_progress_unit_category)
        val total = ordered.size.toLong()
        val slices = prefsSlices(context.getSharedPrefs())

        ZipOutputStream(out).use { zip ->
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("version", FORMAT_VERSION)
                .put("app", context.packageName)
                .put("createdTs", System.currentTimeMillis())
                .put("categories", JSONArray(ordered.map { it.id }))
            writeEntry(zip, "manifest.json", manifest.toString(2).toByteArray())

            ordered.forEachIndexed { index, category ->
                throwIfCancelled(isCancelled)
                val done = (index + 1).toLong()
                val label = context.getString(category.shortLabelRes)
                onProgress(done, total, unit, "$unit $done/$total — $label")
                if (category == Category.MESSAGES) {
                    val json = messagesJson(context, onProgress, isCancelled)
                    throwIfCancelled(isCancelled)
                    writeEntry(zip, "${category.id}.json", json)
                    return@forEachIndexed
                }
                writeEntry(zip, "${category.id}.json", (slices[category] ?: JSONObject()).toString(2).toByteArray())
                if (category == Category.FONTS) {
                    exportFonts(context, zip)
                }
            }
        }
    }

    /**
     * Merge the selected categories of an exported archive into the live prefs (plus font files).
     * Returns a per-category "Label: n" summary of applied keys; categories absent from the
     * archive are skipped. An empty summary means the file contained none of the selected
     * categories, i.e. it is not one of our exports.
     */
    fun import(
        context: Context,
        bytes: ByteArray,
        categories: Set<Category>,
        onProgress: ProgressReporter = { _, _, _, _ -> },
    ): ImportOutcome = import(context, bytes.inputStream(), categories, onProgress)

    /**
     * What an import actually did.
     *
     * [summary] is the per-category "Label: n" text. [refused] names categories that were **in the
     * archive and could not be applied at all** — today only the messages category, when this app
     * does not hold the default-SMS-app role.
     *
     * It is a separate list rather than a zero in the summary because the two mean opposite things
     * to whoever reads them: "0" says the archive had nothing worth restoring, and a refusal says
     * the phone is not ready and the data is still waiting. Reporting the second as the first is
     * exactly the failure this type was added for.
     */
    data class ImportOutcome(val summary: String, val refused: List<String>)

    /** The message store accepted none of the first [REFUSAL_PROBE] writes. Never leaves this file. */
    private class MessagesRefusedException : Exception()

    /**
     * Why the message store refused, and what 白い熊 is to do about it.
     *
     * **Three cases, because they need three different actions** — and because telling 白い熊 "not
     * the default SMS app" while the Settings screen in front of them says it *is* the SMS app is
     * worse than saying nothing (白い熊, 2026-09-08). That is [BLOCKED], and it is a real state: on
     * their EMUI the role was held (`dumpsys role` → `holders=shiroikuma.messeji`) while the
     * `WRITE_SMS` app-op sat at `ignore` and `sms_default_application` was `null` — the role was
     * granted without the side effects that make it mean anything, so re-applying the setting is
     * the cure rather than setting it for the first time.
     *
     * [key] is the stable part a caller may match on; the advice is a resource, so 白い熊 reads it
     * in Japanese.
     */
    enum class WriteRefusal(val key: String, @StringRes val adviceRes: Int) {
        /** The role is genuinely not held. Set it. */
        NOT_DEFAULT("ERROR:not-default-sms-app", R.string.sms_refusal_not_default),

        /** Shown as the SMS app, writes still blocked. Re-apply the setting. */
        BLOCKED("ERROR:sms-write-blocked", R.string.sms_refusal_blocked),

        /** Everything this app can check says it should work, and it did not. */
        UNKNOWN("ERROR:message-store-refused", R.string.sms_refusal_unknown),
    }

    /** Which of [WriteRefusal] applies right now — asked only after writes have actually failed. */
    fun writeRefusal(context: Context): WriteRefusal = when {
        !canWriteMessages(context) -> WriteRefusal.NOT_DEFAULT
        !isWriteSmsAllowed(context) -> WriteRefusal.BLOCKED
        else -> WriteRefusal.UNKNOWN
    }

    /** The instruction on its own, for the in-app dialog. The reply adds [WriteRefusal.key]. */
    fun refusalAdvice(context: Context, refusal: WriteRefusal): String =
        context.getString(refusal.adviceRes, REFUSAL_PROBE)

    /**
     * Whether the `WRITE_SMS` app-op is allowed for us.
     *
     * The op is the thing the provider actually consults, and it is where a half-applied SMS-app
     * grant shows up: `ignore` means every insert is discarded and answered as if it worked. Asked
     * by raw op name rather than through `OPSTR_WRITE_SMS`, whose visibility has moved around
     * between API levels.
     *
     * Defaults to **true** when it cannot be determined: an app that cannot read its own app-op has
     * no business telling 白い熊 their phone is misconfigured.
     */
    private fun isWriteSmsAllowed(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return@runCatching true
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(OP_WRITE_SMS, Process.myUid(), context.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(true)

    private const val OP_WRITE_SMS = "android:write_sms"

    /**
     * How many messages may fail to land before the store is judged to be refusing all of them.
     *
     * Not 1: a single message can legitimately fail on its own merits — an MMS whose addresses yield
     * no usable thread id returns false without anything being wrong with the store. Fifty
     * consecutive failures with nothing written is not that.
     */
    private const val REFUSAL_PROBE = 50

    /**
     * The same import, reading the archive as a **stream**.
     *
     * The automation data door takes this one: it is handed a descriptor onto a caller's file, and an
     * archive carrying this app's whole message corpus (MMS attachments included) has no business
     * being pulled into a byte array first just to be unzipped out of it again. The guarantee is
     * unchanged — [readZip] consumes the entire archive before a single key is applied, so a stream
     * that fails halfway restores nothing rather than half.
     */
    fun import(
        context: Context,
        input: InputStream,
        categories: Set<Category>,
        onProgress: ProgressReporter = { _, _, _, _ -> },
    ): ImportOutcome {
        val entries = readZip(input)
        val summary = StringBuilder()
        val refused = mutableListOf<String>()
        categories.forEach { category ->
            val json = entries["${category.id}.json"] ?: return@forEach
            // The messages category is the one the platform can refuse, and refuse SILENTLY. It is
            // detected from the writes themselves rather than from any role API: asking the role
            // first is what refused a restore on a phone that was holding the role all along.
            var count = if (category == Category.MESSAGES) {
                try {
                    importMessages(context, String(json), onProgress)
                } catch (_: MessagesRefusedException) {
                    refused.add(context.getString(category.labelRes))
                    return@forEach
                }
            } else {
                importPrefs(context.getSharedPrefs(), String(json), category)
            }
            if (category == Category.FONTS) {
                count += importFonts(context, entries)
            }
            if (summary.isNotEmpty()) {
                summary.append('\n')
            }
            summary.append(context.getString(category.labelRes)).append(": ").append(count)
        }
        return ImportOutcome(summary.toString(), refused)
    }

    /**
     * Whether this app is *supposed* to be allowed to write the SMS/MMS store.
     *
     * **The gate is the default-SMS-app role, not a permission.** `READ_SMS` and `WRITE_SMS` can
     * both be granted — they were, on the phone where this was found — and every write still be
     * discarded, because the platform enforces this as an app-op that makes the provider no-op the
     * insert *without throwing and without a null return*. No permission prompt can grant the role;
     * 白い熊 assigns it.
     *
     * **Ask [RoleManager], not the legacy setting.** Q moved this role there and left
     * `Settings.Secure.sms_default_application` behind: on 白い熊's EMUI the role is held
     * (`dumpsys role` → `holders=shiroikuma.messeji`) while that setting is still `null`, so
     * `Telephony.Sms.getDefaultSmsPackage` answers "not us" about a phone perfectly willing to take
     * the writes — and a restore was refused over it (2026-09-08). `MainActivity.loadMessages`
     * already asked the newer question; this now matches it.
     *
     * This is **advisory**. It explains a refusal; it does not decide one. The only thing that
     * decides is whether a row lands — see [importMessages].
     */
    fun canWriteMessages(context: Context): Boolean {
        if (isQPlus()) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_SMS)) {
                return roles.isRoleHeld(RoleManager.ROLE_SMS)
            }
        }
        @Suppress("DEPRECATION")
        return Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }

    /** Every exportable prefs key, sliced into its category's typed key→{t,v} JSON object. */
    private fun prefsSlices(prefs: SharedPreferences): Map<Category, JSONObject> {
        val slices = HashMap<Category, JSONObject>()
        prefs.all.forEach { (key, value) ->
            val category = categoryOf(key) ?: return@forEach
            val entry = typedEntry(value) ?: return@forEach
            slices.getOrPut(category) { JSONObject() }.put(key, entry)
        }
        return slices
    }

    private fun typedEntry(value: Any?): JSONObject? = when (value) {
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is String -> JSONObject().put("t", "s").put("v", value)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
        else -> null
    }

    // Merge a category's typed JSON into prefs. Only keys that (still) classify into the imported
    // category are applied, so a crafted archive can't smuggle keys into other categories.
    private fun importPrefs(prefs: SharedPreferences, json: String, category: Category): Int {
        val obj = JSONObject(json)
        val editor = prefs.edit()
        var count = 0
        obj.keys().forEach { key ->
            if (categoryOf(key) != category) {
                return@forEach
            }
            val entry = obj.optJSONObject(key) ?: return@forEach
            when (entry.optString("t")) {
                "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                "i" -> editor.putInt(key, entry.optInt("v"))
                "l" -> editor.putLong(key, entry.optLong("v"))
                "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                "s" -> editor.putString(key, entry.optString("v"))
                "ss" -> {
                    val array = entry.optJSONArray("v") ?: return@forEach
                    editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                }

                else -> return@forEach
            }
            count++
        }
        // commit(), NOT apply(): 応用管理 force-stops this app the instant an automation import
        // reports success — deliberately, because a process shutting down writes its cached prefs
        // back out and would undo the restore. That force-stop is a SIGKILL, so an apply() still in
        // flight on the async writer is simply lost and the restore reports success over data that
        // never reached disk. This is the one write on the restore path that was not already
        // synchronous: the message rows go through contentResolver.insert, MMS attachment bytes
        // through a closed openOutputStream, and fonts through File.writeBytes — all durable by the
        // time they return. Both callers run on a background thread, so the blocking write is free.
        editor.commit()
        return count
    }

    // The messages themselves, serialized exactly like the stock backup (kotlinx JSON of
    // SmsBackup/MmsBackup with defaults), so archives stay interchangeable with stock exports.
    private fun messagesJson(
        context: Context,
        onProgress: ProgressReporter,
        isCancelled: () -> Boolean,
    ): ByteArray {
        val unit = context.getString(Category.MESSAGES.shortLabelRes)
        var messages: List<MessagesBackup> = emptyList()
        MessagesReader(context).getMessagesToExport(
            getSms = true,
            getMms = true,
            // Best-effort early abort of the one slow phase: commons' queryCursor swallows what a row
            // callback throws, so this ends the current conversation's cursor and the remaining ones
            // unwind a row later. The check after the read is the one that decides.
            onProgress = { done, total ->
                throwIfCancelled(isCancelled)
                if (done == MessagesReader.COUNTING) {
                    // Still counting. Both counts go out as UNKNOWN so that nothing downstream can
                    // compose a fraction out of a denominator that is still moving; the number the
                    // reader does know travels in the words instead.
                    onProgress(
                        AutomationProgress.UNKNOWN,
                        AutomationProgress.UNKNOWN,
                        unit,
                        context.getString(R.string.state_progress_counting, total, unit),
                    )
                } else {
                    onProgress(done.toLong(), total.toLong(), unit, "$unit $done/$total")
                }
            },
        ) { messages = it }
        throwIfCancelled(isCancelled)
        return Json { encodeDefaults = true }.encodeToString(messages).toByteArray()
    }

    private fun importMessages(
        context: Context,
        json: String,
        onProgress: ProgressReporter,
    ): Int {
        val backup = Json.decodeFromString<List<MessagesBackup>>(json)
        val messages = if (isUpsideDownCakePlus()) {
            // same workaround as the stock importer: Android 14 rejects foreign subscription ids
            backup.map { message ->
                when (message) {
                    is SmsBackup -> message.copy(subscriptionId = -1)
                    is MmsBackup -> message.copy(subscriptionId = -1)
                }
            }
        } else {
            backup
        }

        val writer = MessagesWriter(context)
        val unit = context.getString(Category.MESSAGES.shortLabelRes)
        val total = messages.size.toLong()
        var count = 0
        messages.forEachIndexed { index, message ->
            // Only a message the store confirms afterwards is counted. The writers read the row back
            // for us; counting the *attempt*, as this did until 2026-09-08, is what let a restore
            // announce 4,259 messages into an empty provider.
            val written = runCatching {
                when (message) {
                    is SmsBackup -> writer.writeSmsMessage(message)
                    is MmsBackup -> writer.writeMmsMessage(message)
                }
            }.getOrDefault(false)
            if (written) {
                count++
            }
            // Not one of the first [REFUSAL_PROBE] has landed: the store is taking nothing from us,
            // and grinding through thousands more round trips would only prove it more slowly. This
            // is the ONLY test that decides — a role API can disagree with the provider in either
            // direction, and on 白い熊's phone it did.
            if (count == 0 && index + 1 >= REFUSAL_PROBE) {
                throw MessagesRefusedException()
            }
            val done = (index + 1).toLong()
            onProgress(done, total, unit, "$unit $done/$total")
        }
        writer.fixConversationDates()
        refreshConversations()
        return count
    }

    private fun exportFonts(context: Context, zip: ZipOutputStream) {
        FontHelper.getFontsDir(context).listFiles()?.filter { it.isFile }?.forEach { file ->
            writeEntry(zip, FONTS_DIR_ENTRY + file.name, file.readBytes())
        }
    }

    private fun importFonts(context: Context, entries: Map<String, ByteArray>): Int {
        var count = 0
        entries.forEach { (name, bytes) ->
            if (name.startsWith(FONTS_DIR_ENTRY) && name != FONTS_DIR_ENTRY) {
                val safeName = File(name).name // basename only — no path traversal
                if (FontHelper.saveFontData(context, bytes, safeName)) {
                    count++
                }
            }
        }
        return count
    }

    // ---------------------------------------------------------------------------------------------
    // HEADLESS DESTINATION (automation)
    // ---------------------------------------------------------------------------------------------

    /**
     * A resolved headless export destination: where to write, what to call it, how big it ended up,
     * and how to remove it again — [delete] is what a cancelled or failed export calls so the backup
     * directory is left exactly as it was found, with no short archive in it.
     */
    class Target(
        val displayPath: String,
        val open: () -> OutputStream,
        val size: () -> Long,
        val delete: () -> Unit,
    )

    /**
     * Resolve where a headless export writes. Directory precedence, per the automation contract:
     * [pathOverride] (an absolute directory, created if missing) → the configured export folder →
     * null, which the caller reports as "no-directory".
     */
    fun headlessTarget(context: Context, pathOverride: String): Target? {
        val name = exportFileName()
        if (pathOverride.isNotEmpty()) {
            // /sdcard is a symlink; normalize so the MediaStore path checks inside the writer match.
            val primary = Environment.getExternalStorageDirectory().absolutePath
            val file = File(pathOverride.replaceFirst(Regex("^/sdcard"), primary), name)
            file.parentFile?.mkdirs()
            // whichever backend ended up taking the file is also the one that has to remove it
            var mediaUri: Uri? = null
            return Target(
                displayPath = file.absolutePath,
                open = {
                    val uri = mediaStoreUri(context, file)
                    mediaUri = uri
                    if (uri == null) {
                        openDirect(file)
                    } else {
                        context.contentResolver.openOutputStream(uri, "wt") ?: error("cannot open $uri")
                    }
                },
                size = { file.length() },
                delete = {
                    val uri = mediaUri
                    runCatching {
                        if (uri == null) file.delete() else context.contentResolver.delete(uri, null, null)
                    }
                },
            )
        }

        val dir = exportDir(context) ?: return null
        val file = dir.createFile("application/zip", name) ?: error("cannot create a file in ${dir.name}")
        return Target(
            displayPath = displayPathOf(file.uri),
            open = { context.contentResolver.openOutputStream(file.uri) ?: error("cannot open ${file.uri}") },
            size = { file.length() },
            delete = { runCatching { file.delete() } },
        )
    }

    /**
     * Plain-file writer for an absolute path — what lets an arbitrary location work once MediaStore
     * has declined it. On API 30+ that needs All-files access, so name the remedy instead of letting
     * the write fail silently.
     */
    private fun openDirect(file: File): OutputStream {
        val primary = Environment.getExternalStorageDirectory().absolutePath
        if (isRPlus() && file.absolutePath.startsWith("$primary/") && !Environment.isExternalStorageManager()) {
            error(
                "no permission to write ${file.absolutePath} — grant “All files access” to 白い熊 メッセージ " +
                    "(UI page → Export / Import → Automation), or export under Download/ or Documents/"
            )
        }
        file.parentFile?.mkdirs()
        return FileOutputStream(file)
    }

    /**
     * The MediaStore row to write this file to, created if needed — Download/ and Documents/ take
     * non-media files from any app with no permission. null = not a location MediaStore can take, so
     * the caller falls back to a plain file. Each early return is one reason it cannot, answered as
     * soon as it is known.
     */
    @Suppress("ReturnCount")
    private fun mediaStoreUri(context: Context, file: File): Uri? {
        val primary = Environment.getExternalStorageDirectory().absolutePath
        val parent = file.parentFile?.absolutePath ?: return null
        if (!parent.startsWith("$primary/")) {
            return null
        }

        val relativePath = parent.removePrefix("$primary/").trimEnd('/')
        val topDir = relativePath.substringBefore('/')
        if (topDir != Environment.DIRECTORY_DOWNLOADS && topDir != Environment.DIRECTORY_DOCUMENTS) {
            return null
        }

        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // Rewrite our own earlier file of the same name instead of piling up "name (1).zip" copies.
        runCatching {
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val args = arrayOf("$relativePath/", file.name)
            context.contentResolver
                .query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        return ContentUris.withAppendedId(collection, cursor.getLong(0))
                    }
                }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativePath/")
        }
        return context.contentResolver.insert(collection, values)
    }

    /**
     * Best-effort filesystem path for a SAF document ("primary:〇/x.zip" → "/storage/emulated/0/〇/x.zip"),
     * so the automation reply names a path 白い熊 can actually open. Falls back to the URI.
     */
    private fun displayPathOf(uri: Uri): String {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return uri.toString()
        val volume = docId.substringBefore(':', "")
        val relative = docId.substringAfter(':', "")
        if (volume.isEmpty() || relative.isEmpty()) {
            return uri.toString()
        }
        val root = if (volume == "primary") {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        return "$root/$relative"
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readZip(input: InputStream): Map<String, ByteArray> {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return entries
    }
}

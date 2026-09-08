package org.fossify.messages.dialogs

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.themeColor
import org.fossify.messages.helpers.EXIM_WARN_COLOR
import org.fossify.messages.helpers.SettingsEximport

private const val RIPPLE_ALPHA = 0x33000000
private const val PROGRESS_STEP = 20
private const val RGB_MASK = 0x00FFFFFF
private const val DESC_ALPHA = 0.85f
private const val STATUS_ALPHA = 0.8f
private const val WINDOW_CORNER_DP = 8f
private const val WINDOW_STROKE_DP = 2
private const val WINDOW_INSET_DP = 16
private const val PILL_CORNER_DP = 50f
private const val PILL_STROKE_DP = 1.5f

/**
 * The Export/Import panel of the 白い熊 メッセージ UI page (modelled on the Kōjiki fork's category
 * bottom sheet): a settable export directory, a "last export" status line, one checkbox per
 * category and an Arcanechat-style pill button row — Cancel separate on the left, Import and
 * Export on the right. Everything is drawn black-on-black with the accent (yellow) for borders.
 *
 * A successful export or import walks the whole chain shut: info dialog → this panel →
 * the UI page itself ([onChainClosed]); failures only toast and leave the panel open.
 */
@Suppress("TooManyFunctions", "MagicNumber") // the magic numbers are self-evident layout dp/sp
class ExportImportDialog(
    private val activity: Activity,
    private val onPickDirectory: () -> Unit,
    private val onSaveAs: (suggestedName: String) -> Unit,
    private val onPickImportFile: () -> Unit,
    private val onChainClosed: () -> Unit,
) {
    private val accent = activity.themeColor(ThemeSlot.PRIMARY)
    private val fill = activity.themeColor(ThemeSlot.BACKGROUND)
    private val textColor = activity.themeColor(ThemeSlot.TEXT)
    private val density = activity.resources.displayMetrics.density

    private val checks = LinkedHashMap<SettingsEximport.Category, CheckBox>()
    private var selectAll: CheckBox? = null
    private var dirValue: TextView? = null
    private var statusLine: TextView? = null
    private var progressLine: TextView? = null
    private var dialog: AlertDialog? = null
    private var running = false

    init {
        val dialog = AlertDialog.Builder(activity)
            .setView(buildContent())
            .setPositiveButton(R.string.eim_export, null)
            .setNegativeButton(R.string.eim_import, null)
            .setNeutralButton(org.fossify.commons.R.string.cancel, null)
            .show()
        styleWindow(dialog)
        stylePills(dialog)

        // re-attach so the panel does not auto-dismiss on Export/Import
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { onExportClicked() }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { onImportClicked() }
        this.dialog = dialog
        refreshStatus()
    }

    fun dismiss() = dialog?.dismiss()

    private fun dp(value: Int) = (value * density).toInt()

    private fun buildContent(): ScrollView {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
        }

        content.addView(
            text(activity.getString(R.string.eim_title), 18f, accent, bold = true).apply {
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            }
        )
        content.addView(
            text(activity.getString(R.string.eim_desc), 13f, textColor).apply {
                alpha = DESC_ALPHA
                setPadding(0, dp(6), 0, dp(10))
            }
        )

        // persisted export directory — a bordered, clearly tappable box
        val dirBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(fill)
                setStroke(dp(2), accent)
            }
            setOnClickListener { onPickDirectory() }
        }
        dirBox.addView(text(activity.getString(R.string.eim_dir), 12f, accent))
        dirValue = text("", 15f, textColor, bold = true)
        dirBox.addView(dirValue)
        content.addView(dirBox)

        statusLine = text("", 13f, textColor).apply { setPadding(0, dp(8), 0, 0) }
        content.addView(statusLine)

        content.addView(divider())

        selectAll = checkbox(activity.getString(R.string.eim_select_all), bold = true).apply {
            setOnClickListener {
                checks.values.forEach { it.isChecked = isChecked }
            }
        }
        content.addView(selectAll)

        // Which items start ticked is the category's own answer (Category.defaultOn) — the same one
        // LIST_CATEGORIES sends 自由作業盤, so both pickers open on the same selection.
        SettingsEximport.Category.entries.forEach { category ->
            val box = checkbox(activity.getString(category.labelRes), checked = category.defaultOn).apply {
                setOnCheckedChangeListener { _, _ ->
                    selectAll?.isChecked = checks.values.all { it.isChecked }
                }
            }
            checks[category] = box
            content.addView(box)
        }
        selectAll?.isChecked = checks.values.all { it.isChecked }

        // live done/total counter while an export or import runs (messages can be thousands)
        progressLine = text("", 15f, accent, bold = true).apply {
            visibility = android.view.View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        content.addView(progressLine)

        return ScrollView(activity).apply { addView(content) }
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun checkbox(label: String, bold: Boolean = false, checked: Boolean = true) = CheckBox(activity).apply {
        text = label
        isChecked = checked
        textSize = 15f
        setTextColor(textColor)
        buttonTintList = ColorStateList.valueOf(accent)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun divider() = android.view.View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = dp(10)
            bottomMargin = dp(4)
        }
        setBackgroundColor(accent)
    }

    // black rounded window with an accent border (Arcanechat's dialog chrome)
    private fun styleWindow(dialog: AlertDialog) {
        val background = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = WINDOW_CORNER_DP * density
            setStroke(dp(WINDOW_STROKE_DP), accent)
        }
        dialog.window?.setBackgroundDrawable(InsetDrawable(background, dp(WINDOW_INSET_DP)))
    }

    // round accent-bordered pill buttons; the AppCompat button bar already puts the neutral
    // (Cancel) button separate on the left and negative+positive (Import, Export) on the right
    private fun stylePills(dialog: AlertDialog) {
        val buttons = intArrayOf(
            AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL
        )
        buttons.forEach { which ->
            val button: Button = dialog.getButton(which) ?: return@forEach
            val pill = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = PILL_CORNER_DP * density
                setStroke((PILL_STROKE_DP * density).toInt(), accent)
            }
            val ripple = RippleDrawable(
                ColorStateList.valueOf((accent and RGB_MASK) or RIPPLE_ALPHA), pill, null
            )
            button.background = ripple
            button.setTextColor(accent)
            button.setPadding(dp(20), dp(6), dp(20), dp(6))
            (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.marginStart = dp(8)
                button.layoutParams = it
            }
        }
    }

    fun refreshStatus() {
        val name = SettingsEximport.exportDir(activity)?.name
            ?: SettingsEximport.getDirUri(activity)?.lastPathSegment
        dirValue?.text = name ?: activity.getString(R.string.eim_dir_unset)
        dirValue?.setTextColor(if (name == null) EXIM_WARN_COLOR else textColor)
        val (message, isWarning) = SettingsEximport.lastExportStatus(activity)
        statusLine?.text = message
        statusLine?.setTextColor(if (isWarning) EXIM_WARN_COLOR else textColor)
        statusLine?.alpha = if (isWarning) 1f else STATUS_ALPHA
    }

    private fun selected(): Set<SettingsEximport.Category> =
        checks.filterValues { it.isChecked }.keys

    // While an export/import runs, show a live counter and lock the action buttons.
    private fun setRunning(labelRes: Int?) {
        running = labelRes != null
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !running
        dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !running
        progressLine?.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        if (labelRes != null) {
            progressLine?.text = activity.getString(labelRes) + "…"
        }
    }

    // Called from the background thread for every message; only every 20th (and the last) posts
    // to the UI thread so thousands of messages don't flood it. [text] is the export core's ready-made
    // counted line ("メッセージ 1234/8942").
    private fun postProgress(labelRes: Int, current: Long, total: Long, text: String) {
        // A line that carries no count of its own — the counting phase, a heartbeat — is never
        // throttled. "Every PROGRESS_STEP-th" is meaningless without a count, and the arithmetic
        // below would drop every one of them, leaving this dialog blank for exactly the phase the
        // line exists to describe.
        val counted = current >= 0
        if (counted && current % PROGRESS_STEP != 0L && current != total) {
            return
        }
        activity.runOnUiThread {
            progressLine?.text = activity.getString(labelRes) + "… " + text
        }
    }

    private fun onExportClicked() {
        if (running) {
            return
        }
        if (selected().isEmpty()) {
            activity.toast(R.string.eim_none_selected)
            return
        }
        val dir = SettingsEximport.exportDir(activity)
        if (dir == null) {
            onSaveAs(SettingsEximport.exportFileName()) // no folder set → save-as picker
        } else {
            exportToFolder(dir)
        }
    }

    private fun onImportClicked() {
        if (running) {
            return
        }
        if (selected().isEmpty()) {
            activity.toast(R.string.eim_none_selected)
            return
        }
        onPickImportFile()
    }

    private fun exportToFolder(dir: DocumentFile) {
        val categories = selected()
        setRunning(R.string.eim_export)
        ensureBackgroundThread {
            val result = runCatching {
                val name = SettingsEximport.exportFileName()
                val file = dir.createFile("application/zip", name)
                    ?: error("could not create file in folder")
                activity.contentResolver.openOutputStream(file.uri)?.use { stream ->
                    SettingsEximport.export(
                        context = activity,
                        categories = categories,
                        out = stream,
                        onProgress = { current, total, _, text ->
                            postProgress(R.string.eim_export, current, total, text)
                        },
                    )
                } ?: error("no output stream")
                name
            }
            activity.runOnUiThread { finishExport(result) }
        }
    }

    fun exportToUri(uri: Uri) {
        val categories = selected()
        setRunning(R.string.eim_export)
        ensureBackgroundThread {
            val result = runCatching {
                activity.contentResolver.openOutputStream(uri)?.use { stream ->
                    SettingsEximport.export(
                        context = activity,
                        categories = categories,
                        out = stream,
                        onProgress = { current, total, _, text ->
                            postProgress(R.string.eim_export, current, total, text)
                        },
                    )
                } ?: error("no output stream")
                DocumentFile.fromSingleUri(activity, uri)?.name ?: uri.lastPathSegment.orEmpty()
            }
            activity.runOnUiThread { finishExport(result) }
        }
    }

    private fun finishExport(result: Result<String>) {
        setRunning(null)
        result.onSuccess { name ->
            showExportDone(name)
        }.onFailure { e ->
            activity.toast(activity.getString(R.string.eim_export_fail, e.message ?: ""))
        }
    }

    fun importFrom(uri: Uri) {
        val categories = selected()
        setRunning(R.string.eim_import)
        ensureBackgroundThread {
            val bytes = runCatching {
                activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                activity.runOnUiThread {
                    setRunning(null)
                    activity.toast(activity.getString(R.string.eim_import_fail, "no input stream"))
                }
                return@ensureBackgroundThread
            }
            val result = runCatching {
                SettingsEximport.import(activity, bytes, categories) { current, total, _, text ->
                    postProgress(R.string.eim_import, current, total, text)
                }
            }
            activity.runOnUiThread {
                setRunning(null)
                result.onSuccess { outcome ->
                    if (outcome.summary.isEmpty() && outcome.refused.isEmpty()) {
                        activity.toast(R.string.eim_import_none)
                    } else {
                        showImportDone(outcome)
                    }
                }.onFailure { e ->
                    activity.toast(activity.getString(R.string.eim_import_fail, e.message ?: ""))
                }
            }
        }
    }

    // Success dialogs: yellow-bordered black info dialog; acknowledging it closes the whole
    // chain (info dialog → panel → UI page). "Restart now" restarts the app instead.
    private fun showExportDone(name: String) {
        val info = AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.eim_export_ok, name))
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setCancelable(false)
            .show()
        styleInfoDialog(info)
        info.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            info.dismiss()
            closeChain()
        }
    }

    private fun showImportDone(outcome: SettingsEximport.ImportOutcome) {
        // A refusal is stated above the summary, not buried under it: what did not come back is more
        // urgent than what did, and the reason is a setting 白い熊 can change and then re-import.
        val body = if (outcome.refused.isEmpty()) {
            activity.getString(R.string.eim_import_done_body, outcome.summary)
        } else {
            val refusal = SettingsEximport.writeRefusal(activity)
            SettingsEximport.refusalAdvice(activity, refusal) +
                "\n\n" + activity.getString(R.string.eim_import_done_body, outcome.summary)
        }
        val info = AlertDialog.Builder(activity)
            .setTitle(R.string.eim_import_done_title)
            .setMessage(body)
            .setPositiveButton(R.string.eim_restart_now, null)
            .setNegativeButton(R.string.eim_restart_later, null)
            .setCancelable(false)
            .show()
        styleInfoDialog(info)
        info.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { restartApp() }
        info.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            info.dismiss()
            closeChain()
        }
    }

    private fun styleInfoDialog(info: AlertDialog) {
        styleWindow(info)
        stylePills(info)
        info.findViewById<TextView>(android.R.id.message)?.setTextColor(textColor)
        info.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(accent)
    }

    private fun closeChain() {
        dialog?.dismiss()
        onChainClosed()
    }

    private fun restartApp() {
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        activity.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }
}

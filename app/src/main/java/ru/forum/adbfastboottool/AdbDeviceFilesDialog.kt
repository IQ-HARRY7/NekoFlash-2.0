package ru.forum.adbfastboottool

import android.content.DialogInterface
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class AdbDeviceFilesDialog(
    private val activity: AppCompatActivity,
    initialDirectory: String,
    private val list: suspend (String) -> List<AdbDeviceDirectory.Entry>?,
    private val pull: (String) -> Boolean,
    private val send: (String) -> Unit
) {
    private val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
    private val title = TextView(activity).apply { setTextAppearance(android.R.style.TextAppearance_Material_Title) }
    private val sendButton = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        setText(R.string.adb_device_files_push_here)
        setOnClickListener { send(directory) }
    }
    private val header = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPaddingRelative(padding, padding, padding, 0)
        addView(title)
        addView(sendButton, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    private val dialog = MaterialAlertDialogBuilder(activity)
        .setCustomTitle(header)
        .setAdapter(adapter, null)
        .setNegativeButton(R.string.close_upper, null)
        .setNeutralButton(R.string.adb_device_files_up, null)
        .setPositiveButton(R.string.adb_device_files_pull_directory, null)
        .create()
    private var directory = initialDirectory
    private var entries: List<AdbDeviceDirectory.Entry>? = null
    private var loadJob: Job? = null
    private var loadGeneration = 0L

    fun show() {
        dialog.setOnDismissListener {
            loadGeneration++
            loadJob?.cancel()
        }
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            AdbDeviceDirectory.parent(directory)?.let(::open)
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            if (pull(directory.trimEnd('/') + "/")) dialog.dismiss()
        }
        dialog.listView?.setOnItemClickListener { _, _, position, _ ->
            val entry = entries?.getOrNull(position) ?: return@setOnItemClickListener
            val path = AdbDeviceDirectory.child(directory, entry.name)
            if (entry.isDirectory) open(path) else if (pull(path)) dialog.dismiss()
        }
        open(directory)
    }

    fun dismiss() = dialog.dismiss()
    val isShowing: Boolean get() = dialog.isShowing

    private fun open(path: String) {
        loadJob?.cancel()
        val generation = ++loadGeneration
        directory = path
        entries = null
        title.text = activity.getString(R.string.adb_device_files_location, path)
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = false
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false
        sendButton.isEnabled = false
        replaceRows(listOf(activity.getString(R.string.adb_device_files_loading)))
        loadJob = activity.lifecycleScope.launch {
            val listing = list(path)
            if (generation != loadGeneration || !dialog.isShowing) return@launch
            entries = listing
            val rows = when {
                listing == null -> listOf(activity.getString(R.string.adb_device_files_unavailable))
                listing.isEmpty() -> listOf(activity.getString(R.string.adb_device_files_no_entries))
                else -> listing.map { if (it.isDirectory) "📁 ${it.name}" else it.name }
            }
            replaceRows(rows)
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = AdbDeviceDirectory.parent(path) != null
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = listing != null
            sendButton.isEnabled = true
        }
    }

    private fun replaceRows(rows: List<String>) {
        adapter.setNotifyOnChange(false)
        adapter.clear()
        adapter.addAll(rows)
        adapter.notifyDataSetChanged()
    }
}

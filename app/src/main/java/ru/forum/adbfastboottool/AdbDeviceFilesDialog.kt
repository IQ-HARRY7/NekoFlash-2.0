package ru.forum.adbfastboottool

import android.content.DialogInterface
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class AdbDeviceFilesDialog(
    private val activity: AppCompatActivity,
    private val list: suspend (String) -> List<AdbDeviceDirectory.Entry>?,
    private val pull: (String) -> Boolean
) {
    private val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
    private val dialog = MaterialAlertDialogBuilder(activity)
        .setAdapter(adapter, null)
        .setNegativeButton(R.string.close_upper, null)
        .setNeutralButton(R.string.adb_device_files_up, null)
        .setPositiveButton(R.string.adb_device_files_pull_directory, null)
        .create()
    private var directory = "/sdcard"
    private var entries: List<AdbDeviceDirectory.Entry>? = null
    private var loadJob: Job? = null

    fun show() {
        dialog.setOnDismissListener { loadJob?.cancel() }
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

    private fun open(path: String) {
        directory = path
        entries = null
        dialog.setTitle(activity.getString(R.string.adb_device_files_location, path))
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = false
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false
        replaceRows(listOf(activity.getString(R.string.adb_device_files_loading)))
        loadJob = activity.lifecycleScope.launch {
            val listing = list(path)
            entries = listing
            val rows = when {
                listing == null -> listOf(activity.getString(R.string.adb_device_files_unavailable))
                listing.isEmpty() -> listOf(activity.getString(R.string.adb_device_files_no_entries))
                else -> listing.map { if (it.isDirectory) "📁 ${it.name}" else it.name }
            }
            replaceRows(rows)
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = AdbDeviceDirectory.parent(path) != null
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = listing != null
        }
    }

    private fun replaceRows(rows: List<String>) {
        adapter.setNotifyOnChange(false)
        adapter.clear()
        adapter.addAll(rows)
        adapter.notifyDataSetChanged()
    }
}

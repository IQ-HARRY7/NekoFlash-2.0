package ru.forum.adbfastboottool

import android.app.Dialog
import android.content.ContentResolver
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class MassFlashImageMappingDialog : DialogFragment() {
    private var restoredPartitions: List<String> = emptyList()
    private val editors = mutableListOf<EditText>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val uris = requireArguments().getStringArrayList(URIS).orEmpty()
        val presets = requireArguments().getStringArrayList(PARTITIONS).orEmpty()
        restoredPartitions = savedInstanceState?.getStringArrayList(PARTITIONS).orEmpty()
        val unnamed = getString(R.string.rev13_unnamed_image)
        val resolver = context.contentResolver
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(TextView(context).apply { setText(R.string.mass_flash_loading_images) })
        }
        val scroll = ScrollView(context).apply { addView(content) }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mass_flash_assign_partitions)
            .setView(scroll)
            .setNegativeButton(R.string.cancel_upper, null)
            .setPositiveButton(R.string.mass_flash_add_to_queue, null)
            .create()
        dialog.setOnShowListener {
            val add = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            add.isEnabled = false
            lifecycleScope.launch {
                val names = withContext(Dispatchers.IO) { uris.map { readDisplayName(resolver, Uri.parse(it), unnamed) } }
                if (this@MassFlashImageMappingDialog.dialog !== dialog) return@launch
                content.removeAllViews()
                editors.clear()
                names.forEachIndexed { index, name ->
                    val suggestion = name.substringBeforeLast('.').takeIf { it in presets }.orEmpty()
                    val editor = EditText(context).apply {
                        id = View.generateViewId()
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        setSingleLine(true)
                        setHint(R.string.flash_rev7_manual_hint)
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                        setText(restoredPartitions.getOrNull(index) ?: suggestion)
                    }
                    content.addView(TextView(context).apply {
                        text = name
                        labelFor = editor.id
                        setPadding(0, dp(8), 0, 0)
                    })
                    content.addView(editor)
                    editors += editor
                }
                add.isEnabled = names.isNotEmpty()
                add.setOnClickListener {
                    val targets = editors.map { FlashOperationDraftPolicy.normalizePartition(it.text.toString()) }
                    val invalid = targets.indexOf(null)
                    if (invalid >= 0) {
                        editors[invalid].apply {
                            error = getString(R.string.flash_rev7_invalid_target)
                            requestFocus()
                        }
                        return@setOnClickListener
                    }
                    parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply {
                        putStringArrayList(URIS, ArrayList(uris))
                        putStringArrayList(NAMES, ArrayList(names))
                        putStringArrayList(PARTITIONS, ArrayList(targets.filterNotNull()))
                    })
                    dismiss()
                }
            }
        }
        return dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val values = if (editors.isEmpty()) restoredPartitions else editors.map { it.text.toString() }
        outState.putStringArrayList(PARTITIONS, ArrayList(values))
    }

    override fun onDestroyView() {
        editors.clear()
        super.onDestroyView()
    }

    companion object {
        const val RESULT_KEY = "mass_flash_image_mapping"
        private const val URIS = "image_uris"
        private const val NAMES = "image_names"
        private const val PARTITIONS = "image_partitions"

        fun create(uris: List<Uri>, presets: List<String>) = MassFlashImageMappingDialog().apply {
            arguments = Bundle().apply {
                putStringArrayList(URIS, ArrayList(uris.map(Uri::toString)))
                putStringArrayList(PARTITIONS, ArrayList(presets))
            }
        }

        fun readResult(result: Bundle): List<Pair<String, FirmwareSource>> {
            val uris = result.getStringArrayList(URIS).orEmpty()
            val names = result.getStringArrayList(NAMES).orEmpty()
            val partitions = result.getStringArrayList(PARTITIONS).orEmpty()
            return uris.zip(names).zip(partitions) { (uri, name), partition ->
                partition to FirmwareSource.SafDocument(Uri.parse(uri), name)
            }
        }

        private fun readDisplayName(resolver: ContentResolver, uri: Uri, unnamed: String): String {
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: unnamed
            return WorkspaceImportNaming.sanitizeImportedFileName(name)
        }
    }
}

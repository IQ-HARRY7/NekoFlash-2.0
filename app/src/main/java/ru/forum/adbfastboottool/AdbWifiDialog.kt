package ru.forum.adbfastboottool

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class AdbWifiDialog(
    private val context: Context,
    private val viewModel: DeviceViewModel,
    private val scope: CoroutineScope
) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val margin = (24 * resources.displayMetrics.density).toInt()
        setPaddingRelative(margin, 0, margin, margin / 2)
    }
    private val status = TextView(context).apply { visibility = View.GONE }

    fun show(): AlertDialog {
        content.addView(TextView(context).apply { setText(R.string.adb_wifi_instructions) })
        val host = addField(R.string.adb_wifi_host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val connectPort = addField(R.string.adb_wifi_connection_port, InputType.TYPE_CLASS_NUMBER)
        val pairPort = addField(R.string.adb_wifi_pairing_port, InputType.TYPE_CLASS_NUMBER)
        val code = addField(R.string.adb_wifi_pairing_code, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        content.addView(status)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.adb_wifi_title)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(R.string.adb_wifi_connect, null)
            .setNeutralButton(R.string.adb_wifi_pair, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        if (viewModel.adbProtocol?.isWireless == true) {
            content.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.adb_wifi_disconnect)
                setOnClickListener {
                    if (viewModel.adbProtocol?.isWireless == true) viewModel.disconnectCurrent()
                    dialog.dismiss()
                }
            })
        }

        var pairingJob: Job? = null
        dialog.setOnDismissListener { pairingJob?.cancel() }
        dialog.show()
        val connectButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val pairButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        connectButton.setOnClickListener {
            val address = readHost(host) ?: return@setOnClickListener
            val port = readPort(connectPort) ?: return@setOnClickListener
            viewModel.connectWirelessAdb(address, port)
            dialog.dismiss()
        }
        pairButton.setOnClickListener {
            val address = readHost(host) ?: return@setOnClickListener
            val port = readPort(pairPort) ?: return@setOnClickListener
            val pin = code.text.toString().trim()
            if (pin.length != 6 || pin.any { it !in '0'..'9' }) {
                code.error = context.getString(R.string.adb_wifi_code_error)
                code.requestFocus()
                return@setOnClickListener
            }
            code.text?.clear()
            showStatus(R.string.adb_wifi_pairing)
            pairButton.isEnabled = false
            connectButton.isEnabled = false
            pairingJob = scope.launch {
                try {
                    viewModel.pairWirelessAdb(address, port, pin)
                    showStatus(R.string.adb_wifi_paired)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    showStatus(R.string.adb_wifi_pair_failed)
                } finally {
                    pairButton.isEnabled = true
                    connectButton.isEnabled = true
                }
            }
        }
        return dialog
    }

    private fun addField(label: Int, fieldType: Int): TextInputEditText {
        val container = TextInputLayout(context).apply { hint = context.getString(label) }
        val input = TextInputEditText(container.context).apply {
            setSingleLine()
            inputType = fieldType
        }
        container.addView(input)
        content.addView(container)
        return input
    }

    private fun readHost(field: TextInputEditText): String? {
        val host = field.text.toString().trim().removeSurrounding("[", "]")
        if (host.isNotEmpty()) return host
        field.error = context.getString(R.string.adb_wifi_host_error)
        field.requestFocus()
        return null
    }

    private fun readPort(field: TextInputEditText): Int? {
        val port = field.text.toString().trim().toIntOrNull()
        if (port != null && port in 1..65535) return port
        field.error = context.getString(R.string.adb_wifi_port_error)
        field.requestFocus()
        return null
    }

    private fun showStatus(message: Int) {
        status.visibility = View.VISIBLE
        status.setText(message)
    }
}

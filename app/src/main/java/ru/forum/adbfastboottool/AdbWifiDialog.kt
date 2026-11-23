package ru.forum.adbfastboottool

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Short discovery-first Wireless ADB panel. The proven transport and pairing client are reused. */
internal class AdbWifiDialog(
    private val context: Context,
    private val viewModel: DeviceViewModel,
    private val scope: CoroutineScope,
    private val draft: Draft,
    private val updateDraft: (Draft) -> Unit
) {
    data class Draft(
        val address: String = "",
        val connectionPort: String = "",
        val pairingPort: String = ""
    ) {
        fun toBundle() = Bundle().apply {
            putString("address", address)
            putString("connect_port", connectionPort)
            putString("pairing_port", pairingPort)
        }

        companion object {
            fun fromBundle(bundle: Bundle?) = Draft(
                address = bundle?.getString("address").orEmpty(),
                connectionPort = bundle?.getString("connect_port").orEmpty(),
                pairingPort = bundle?.getString("pairing_port").orEmpty()
            )
        }
    }

    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()

    private fun info(resource: Int) = TextView(context).apply {
        setText(resource)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun field(parent: LinearLayout, label: Int, type: Int): TextInputEditText {
        val box = TextInputLayout(context).apply { hint = context.getString(label) }
        val field = TextInputEditText(box.context).apply {
            setSingleLine()
            inputType = type
        }
        box.addView(field)
        parent.addView(box)
        return field
    }

    private fun button(label: Int, action: () -> Unit) =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(label)
            setAllCaps(false)
            setOnClickListener { action() }
        }

    fun show(): AlertDialog {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(20), dp(8), dp(20), dp(12))
        }
        root.addView(info(R.string.adb_wifi_discovery_guide))
        val heading = info(R.string.adb_wifi_discovery_searching)
        root.addView(heading)
        val discovered = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(discovered)
        val code = field(root, R.string.adb_wifi_pairing_code,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val status = info(R.string.adb_wifi_discovery_searching).apply { visibility = View.GONE }
        root.addView(status)

        val advanced = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val host = field(advanced, R.string.adb_wifi_host,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply { setText(draft.address) }
        val connectPort = field(advanced, R.string.adb_wifi_connection_port,
            InputType.TYPE_CLASS_NUMBER).apply { setText(draft.connectionPort) }
        val pairPort = field(advanced, R.string.adb_wifi_pairing_port,
            InputType.TYPE_CLASS_NUMBER).apply { setText(draft.pairingPort) }
        for (f in listOf(host, connectPort, pairPort)) {
            f.doAfterTextChanged {
                updateDraft(Draft(host.text.toString(), connectPort.text.toString(), pairPort.text.toString()))
            }
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.adb_wifi_title)
            .setView(ScrollView(context).apply { addView(root) })
            .setNegativeButton(R.string.close_upper, null)
            .create()

        var pairingJob: Job? = null
        var discovery: AdbWifiDiscovery? = null
        var candidates: List<AdbWifiDiscovery.Endpoint> = emptyList()
        var pairedGuid: String? = null
        var connecting = false

        fun showStatus(resource: Int) {
            status.setText(resource)
            status.visibility = View.VISIBLE
        }

        fun connect(endpoint: AdbWifiDiscovery.Endpoint) {
            if (connecting) return
            connecting = true
            updateDraft(Draft(endpoint.host, endpoint.port.toString(), pairPort.text.toString()))
            // Do not declare success here: DeviceViewModel reports the real connection state.
            viewModel.connectWirelessAdb(endpoint.host, endpoint.port)
            dialog.dismiss()
        }

        fun connectIfPaired() {
            val guid = pairedGuid ?: return
            val endpoint = candidates.firstOrNull {
                it.kind == AdbWifiDiscovery.Kind.CONNECT &&
                    AdbWifiEndpointMatch.isPairMate(it.name, guid)
            }
            if (endpoint == null) showStatus(R.string.adb_wifi_discovery_wait_connect)
            else connect(endpoint)
        }

        fun pair(targetHost: String, port: Int) {
            if (pairingJob?.isActive == true) return
            val pin = code.text.toString().trim()
            if (pin.length != 6 || pin.any { it !in '0'..'9' }) {
                code.error = context.getString(R.string.adb_wifi_code_error)
                code.requestFocus()
                return
            }
            showStatus(R.string.adb_wifi_pairing)
            pairingJob = scope.launch {
                try {
                    pairedGuid = viewModel.pairWirelessAdb(targetHost, port, pin)
                    code.text?.clear()
                    showStatus(R.string.adb_wifi_paired)
                    connectIfPaired()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    showStatus(R.string.adb_wifi_pair_failed)
                }
            }
        }

        fun render(endpoints: List<AdbWifiDiscovery.Endpoint>, failed: Boolean) {
            candidates = endpoints
            discovered.removeAllViews()
            heading.setText(when {
                endpoints.isNotEmpty() -> R.string.adb_wifi_discovery_found
                failed -> R.string.adb_wifi_discovery_unavailable
                else -> R.string.adb_wifi_discovery_searching
            })
            endpoints.forEach { endpoint ->
                discovered.addView(TextView(context).apply {
                    text = context.getString(
                        if (endpoint.kind == AdbWifiDiscovery.Kind.PAIR)
                            R.string.adb_wifi_discovery_pair_device
                        else R.string.adb_wifi_discovery_connect_device,
                        endpoint.host, endpoint.port
                    )
                    setPadding(0, dp(5), 0, 0)
                })
                discovered.addView(button(
                    if (endpoint.kind == AdbWifiDiscovery.Kind.PAIR)
                        R.string.adb_wifi_pair else R.string.adb_wifi_connect
                ) {
                    if (endpoint.kind == AdbWifiDiscovery.Kind.PAIR) pair(endpoint.host, endpoint.port)
                    else connect(endpoint)
                })
            }
            connectIfPaired()
        }

        fun startDiscovery() {
            discovery?.close()
            candidates = emptyList()
            discovered.removeAllViews()
            heading.setText(R.string.adb_wifi_discovery_searching)
            discovery = AdbWifiDiscovery(context, ::render).also { it.start() }
        }

        root.addView(button(R.string.adb_wifi_manual_expand) {
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        advanced.addView(button(R.string.adb_wifi_pair) {
            val address = readAddress(host) ?: return@button
            val port = readPort(pairPort) ?: return@button
            pair(address.host, port)
        })
        advanced.addView(button(R.string.adb_wifi_connect) {
            val address = readAddress(host) ?: return@button
            val port = address.port ?: readPort(connectPort) ?: return@button
            connect(AdbWifiDiscovery.Endpoint("manual", address.host, port, AdbWifiDiscovery.Kind.CONNECT))
        })
        root.addView(advanced)
        root.addView(button(R.string.adb_wifi_discovery_rescan) {
            // Preserve a successful pairing while retrying a delayed CONNECT advertisement.
            startDiscovery()
        })
        if (viewModel.adbProtocol?.isWireless == true) {
            root.addView(button(R.string.adb_wifi_disconnect) {
                if (viewModel.adbProtocol?.isWireless == true) viewModel.disconnectCurrent()
                dialog.dismiss()
            })
        }

        dialog.setOnDismissListener {
            pairingJob?.cancel()
            discovery?.close()
        }
        dialog.show()
        startDiscovery()
        return dialog
    }

    private fun readAddress(field: TextInputEditText): AdbWifiAddress? {
        val address = AdbWifiAddress.parse(field.text.toString())
        if (address != null) return address
        field.error = context.getString(R.string.adb_wifi_host_error)
        field.requestFocus()
        return null
    }

    private fun readPort(field: TextInputEditText): Int? {
        val port = AdbWifiAddress.parsePort(field.text.toString())
        if (port != null) return port
        field.error = context.getString(R.string.adb_wifi_port_error)
        field.requestFocus()
        return null
    }
}

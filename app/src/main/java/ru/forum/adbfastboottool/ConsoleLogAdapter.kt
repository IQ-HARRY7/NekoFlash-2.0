package ru.forum.adbfastboottool

import android.view.LayoutInflater
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Append-oriented renderer for the unified Console output.
 *
 * Each log entry is an independent RecyclerView row, so Android only lays out
 * the visible portion of a long session. The adapter keeps stable IDs and
 * exposes narrow mutation methods used by MainActivity's batched renderer.
 */
internal class ConsoleLogAdapter : RecyclerView.Adapter<ConsoleLogAdapter.LineViewHolder>() {
    internal data class Item(
        val id: Long,
        val text: String,
        val tone: CompactLogRenderPolicy.Tone,
    )

    private val items = ArrayList<Item>()
    private var nextId = 1L
    private var textSizeSp = 12f

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_console_line, parent, false) as TextView
        return LineViewHolder(view)
    }

    override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
        holder.bind(items[position], textSizeSp)
    }

    fun replaceAll(lines: List<String>) {
        val previousSize = items.size
        if (previousSize > 0) {
            items.clear()
            notifyItemRangeRemoved(0, previousSize)
        }
        if (lines.isNotEmpty()) {
            items.addAll(lines.map(::newItem))
            notifyItemRangeInserted(0, lines.size)
        }
    }

    fun removeFirst(count: Int) {
        val actual = count.coerceIn(0, items.size)
        if (actual == 0) return
        items.subList(0, actual).clear()
        notifyItemRangeRemoved(0, actual)
    }

    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        val start = items.size
        items.addAll(lines.map(::newItem))
        notifyItemRangeInserted(start, lines.size)
    }

    fun setTextSizeSp(value: Float) {
        textSizeSp = value.coerceIn(9f, 20f)
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    private fun newItem(rawLine: String): Item {
        val formatted = CompactLogRenderPolicy.format(rawLine)
        return Item(
            id = nextId++,
            text = formatted.text,
            tone = formatted.tone,
        )
    }

    internal class LineViewHolder(
        private val textView: TextView,
    ) : RecyclerView.ViewHolder(textView) {
        fun bind(item: Item, textSizeSp: Float) {
            val isWelcome = TerminalWelcomeMessage.isWelcomeLine(item.text)
            textView.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (isWelcome) TerminalWelcomeMessage.TEXT_SIZE_SP else textSizeSp,
            )
            textView.setLineSpacing(0f, 1f)
            textView.setSingleLine(isWelcome)
            textView.maxLines = if (isWelcome) 1 else Int.MAX_VALUE
            textView.ellipsize = if (isWelcome) TextUtils.TruncateAt.END else null
            val defaultColor = ContextCompat.getColor(textView.context, item.tone.colorResource())
            if (!isWelcome) {
                textView.text = item.text
                textView.setTextColor(defaultColor)
                return
            }

            val line = item.text
            val styled = SpannableString(line)
            fun color(start: Int, end: Int, colorRes: Int) {
                if (start < end) styled.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(textView.context, colorRes)),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }

            val frame = R.color.terminal_welcome_frame
            color(0, line.length, R.color.terminal_welcome_text)
            when {
                line.startsWith("╔") || line.startsWith("╚") || line.startsWith("╰") || line.startsWith("=") ->
                    color(0, line.length, frame)
                line.startsWith("╟") -> color(0, line.length, R.color.terminal_welcome_section)
                line.startsWith("║ ") -> {
                    color(0, 1, frame)
                    color(line.length - 1, line.length, frame)
                    val bodyStart = 2
                    val bodyEnd = (line.length - 2).coerceAtLeast(bodyStart)
                    val body = line.substring(bodyStart, bodyEnd).trimEnd()
                    val value = body.trimStart()
                    val bodyColor = when {
                        value.contains("Welcome to NekoFlash-2.0") -> R.color.terminal_welcome_title
                        value.contains("LOCAL TERMINAL") -> R.color.terminal_welcome_cyan
                        value.startsWith("export NEKO_USE_DEVICE_BINARIES=1") -> R.color.terminal_welcome_enabled
                        value.startsWith("export NEKO_USE_DEVICE_BINARIES=0") -> R.color.terminal_welcome_reset
                        value.startsWith("i ") || value.contains("local help") -> R.color.terminal_welcome_help
                        value.startsWith("✓") || value.startsWith(">") -> R.color.terminal_welcome_enabled
                        value.startsWith("<") -> R.color.terminal_welcome_reset
                        value.startsWith("->") -> R.color.terminal_welcome_source
                        value.startsWith("@") || value.startsWith("#") || value.startsWith("*") ||
                            value.startsWith("+") || value.startsWith("~") ->
                            R.color.terminal_welcome_source
                        value.startsWith("!") -> R.color.terminal_welcome_note
                        value.startsWith("⇄") -> R.color.terminal_welcome_cyan
                        else -> R.color.terminal_welcome_text
                    }
                    color(bodyStart, bodyEnd, bodyColor)
                    val separator = line.indexOf(" = ", bodyStart)
                    if (separator in (bodyStart + 1) until bodyEnd) {
                        color(bodyStart, separator + 1, R.color.terminal_welcome_label)
                    }
                    if (body.contains("Welcome to NekoFlash-2.0")) {
                        val brand = line.indexOf("NekoFlash-2.0", bodyStart)
                        if (brand >= 0) color(brand, brand + "NekoFlash-2.0".length, R.color.terminal_welcome_cyan)
                    }
                }
            }
            textView.text = styled
            textView.setTextColor(defaultColor)
        }
    }
}

@ColorRes
private fun CompactLogRenderPolicy.Tone.colorResource(): Int = when (this) {
    CompactLogRenderPolicy.Tone.ERROR -> R.color.log_error
    CompactLogRenderPolicy.Tone.HINT -> R.color.log_hint
    CompactLogRenderPolicy.Tone.SUCCESS -> R.color.log_success
    CompactLogRenderPolicy.Tone.COMMAND -> R.color.log_command
    CompactLogRenderPolicy.Tone.WARNING -> R.color.log_warning
    CompactLogRenderPolicy.Tone.SYSTEM -> R.color.log_system
    CompactLogRenderPolicy.Tone.INFO -> R.color.log_info
    CompactLogRenderPolicy.Tone.LOCAL_OUTPUT -> R.color.log_local_output
    CompactLogRenderPolicy.Tone.TRANSPORT -> R.color.log_transport
    CompactLogRenderPolicy.Tone.FASTBOOT -> R.color.log_fastboot
    CompactLogRenderPolicy.Tone.FILE -> R.color.log_file
    CompactLogRenderPolicy.Tone.DATA -> R.color.log_data
    CompactLogRenderPolicy.Tone.OPERATION -> R.color.log_operation
    CompactLogRenderPolicy.Tone.INVENTORY -> R.color.log_inventory
}

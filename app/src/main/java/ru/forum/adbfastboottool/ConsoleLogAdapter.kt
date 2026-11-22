package ru.forum.adbfastboottool

import android.view.LayoutInflater
import android.text.SpannableString
import android.text.Spanned
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
            val isWelcome = item.text.startsWith("┌─ NEKOFLASH")
            if (!isWelcome) {
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                textView.text = item.text
                textView.setTextColor(ContextCompat.getColor(textView.context, item.tone.colorResource()))
                return
            }

            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            val styled = SpannableString(item.text)
            fun color(start: Int, end: Int, colorRes: Int) {
                val safeStart = start.coerceIn(0, styled.length)
                val safeEnd = end.coerceIn(safeStart, styled.length)
                if (safeStart < safeEnd) {
                    styled.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(textView.context, colorRes)),
                        safeStart, safeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }

            var offset = 0
            item.text.split('\n').forEach { line ->
                val end = offset + line.length
                if (line.startsWith("┌") || line.startsWith("├") || line.startsWith("└")) {
                    color(offset, end, R.color.terminal_welcome_frame)
                    val brand = line.indexOf("NEKOFLASH")
                    if (brand >= 0) color(offset + brand, offset + brand + 9, R.color.terminal_welcome_title)
                    val terminal = line.indexOf("LOCAL TERMINAL")
                    if (terminal >= 0) color(offset + terminal, offset + terminal + 14, R.color.terminal_welcome_cyan)
                } else if (line.length >= 2) {
                    color(offset, offset + 1, R.color.terminal_welcome_frame)
                    color(end - 1, end, R.color.terminal_welcome_frame)
                    val body = line.substring(1, line.length - 1)
                    val value = body.trim()
                    val leading = body.indexOf(value).coerceAtLeast(0)
                    val valueStart = offset + 1 + leading
                    val valueEnd = valueStart + value.length

                    when {
                        value.startsWith("◈ ") -> color(valueStart, valueEnd, R.color.terminal_welcome_section)
                        value.startsWith("● HOST") || value.startsWith("⌂ WORKSPACE") || value.startsWith("⌂ ПАПКА") ->
                            color(valueStart, valueEnd, R.color.terminal_welcome_source)
                        value.startsWith("export NEKO_USE_DEVICE_BINARIES") ->
                            color(valueStart, valueEnd, R.color.terminal_welcome_cyan)
                        value.startsWith("APP ") -> color(valueStart, valueStart + 3, R.color.terminal_welcome_command)
                        value.startsWith("DEVICE ") -> color(valueStart, valueStart + 6, R.color.terminal_welcome_source)
                        value.startsWith("&&") || value.startsWith("|") -> {
                            val operatorLength = if (value.startsWith("&&")) 2 else 1
                            color(valueStart, valueStart + operatorLength, R.color.terminal_welcome_note)
                        }
                        value.startsWith("› local ") || value.startsWith("echo ") || value.startsWith("clear ") ||
                            value.startsWith("cat ") || value.startsWith("local cat ") -> {
                            val commandEnd = value.indexOf("  ").let { if (it < 0) value.length else it }
                            color(valueStart, valueStart + commandEnd, R.color.terminal_welcome_command)
                            value.indexOf("&&").takeIf { it >= 0 }?.let {
                                color(valueStart + it, valueStart + it + 2, R.color.terminal_welcome_note)
                            }
                            value.indexOf('|').takeIf { it >= 0 }?.let {
                                color(valueStart + it, valueStart + it + 1, R.color.terminal_welcome_note)
                            }
                        }
                        value.startsWith("rm asks") || value.startsWith("cp / mv:") || value.startsWith("No root") ||
                            value.startsWith("Scripts") || value.startsWith("Удаление") ||
                            value.startsWith("Root нет") || value.startsWith("Скрипты") ->
                            color(valueStart, valueEnd, R.color.terminal_welcome_note)
                        value.startsWith("Prefer device tools") || value.startsWith("Missing tools") ||
                            value.startsWith("Сначала системные") || value.startsWith("Если нет") ->
                            color(valueStart, valueEnd, R.color.terminal_welcome_source)
                        value.startsWith("ADB / FASTBOOT") || value.startsWith("Локальные команды") ->
                            color(valueStart, valueEnd, R.color.terminal_welcome_cyan)
                        else -> color(valueStart, valueEnd, R.color.terminal_welcome_text)
                    }
                }
                offset = end + 1
            }
            textView.text = styled
            textView.setTextColor(ContextCompat.getColor(textView.context, R.color.terminal_welcome_text))
        }
    }
}

@ColorRes
private fun CompactLogRenderPolicy.Tone.colorResource(): Int = when (this) {
    CompactLogRenderPolicy.Tone.ERROR -> R.color.log_error
    CompactLogRenderPolicy.Tone.HINT -> R.color.log_hint
    CompactLogRenderPolicy.Tone.SUCCESS -> R.color.log_success
    CompactLogRenderPolicy.Tone.COMMAND -> R.color.log_command
    CompactLogRenderPolicy.Tone.WARNING -> R.color.log_hint
    CompactLogRenderPolicy.Tone.SYSTEM -> R.color.log_system
    CompactLogRenderPolicy.Tone.INFO -> R.color.log_info
}

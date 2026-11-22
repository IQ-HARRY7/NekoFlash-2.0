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
            val isWelcome = item.text.startsWith("◆ NEKOFLASH • LOCAL TERMINAL") || item.text.startsWith("◆ NEKOFLASH • ЛОКАЛЬНЫЙ ТЕРМИНАЛ")
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            val defaultColor = ContextCompat.getColor(textView.context, item.tone.colorResource())
            if (isWelcome) {
                val styled = SpannableString(item.text)
                var start = 0
                item.text.splitToSequence('\n').forEach { line ->
                    val end = start + line.length
                    val color = when {
                        line.startsWith("◆ NEKOFLASH") -> R.color.terminal_brand
                        line.startsWith("◆ ") -> R.color.terminal_section
                        line.startsWith("local ") || line.startsWith("echo ") || line == "clear" -> R.color.terminal_local
                        line.startsWith("export NEKO_USE_DEVICE_BINARIES") -> R.color.terminal_device
                        line.startsWith("&&") || line.startsWith("| ") -> R.color.terminal_operator
                        line.startsWith("App commands") || line.startsWith("Device tools") ||
                            line.startsWith("Команды приложения") || line.startsWith("Утилиты Android") -> R.color.terminal_device
                        else -> R.color.terminal_detail
                    }
                    if (end > start) {
                        styled.setSpan(
                            ForegroundColorSpan(ContextCompat.getColor(textView.context, color)),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                    start = end + 1
                }
                textView.text = styled
                textView.setTextColor(defaultColor)
            } else {
                textView.text = item.text
                textView.setTextColor(defaultColor)
            }
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

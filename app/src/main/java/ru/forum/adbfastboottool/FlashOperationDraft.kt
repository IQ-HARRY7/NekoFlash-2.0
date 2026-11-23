package ru.forum.adbfastboottool

import java.io.File
import java.net.URI
import java.util.Base64

/** Lightweight persistable queue entry. No pre-hashing or mutation authorization state. */
data class FlashQueueDraftItem(
    val partition: String,
    val sourceUri: String,
    val displayName: String,
    val expectedSizeBytes: Long,
    val addedAtEpochMs: Long
)

data class FlashOperationDraft(
    val items: List<FlashQueueDraftItem> = emptyList(),
    val revision: Long = 0L,
    val slot: String? = null
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

object FlashOperationDraftPolicy {
    private val PARTITION_PATTERN = Regex("^[A-Za-z0-9._:-]+$")

    fun createItem(
        partition: String,
        file: File,
        addedAtEpochMs: Long = System.currentTimeMillis()
    ): FlashQueueDraftItem {
        val normalizedPartition = normalizePartition(partition)
            ?: throw IllegalArgumentException("Invalid partition name")
        val canonical = file.canonicalFile
        require(canonical.exists() && canonical.isFile && canonical.canRead()) {
            "Source file is not readable"
        }
        require(canonical.length() > 0L) { "Source file is empty" }
        return FlashQueueDraftItem(
            partition = normalizedPartition,
            sourceUri = canonical.toURI().toASCIIString(),
            displayName = canonical.name,
            expectedSizeBytes = canonical.length(),
            addedAtEpochMs = addedAtEpochMs
        )
    }

    fun upsert(draft: FlashOperationDraft, item: FlashQueueDraftItem): FlashOperationDraft {
        require(isPersistable(item)) { "Draft item is not persistable" }
        val next = LinkedHashMap<String, FlashQueueDraftItem>()
        // LinkedHashMap.put replaces an existing target without changing its position.
        // Visible order is execution order; names are concrete targets, including _a/_b.
        draft.items.forEach { existing -> next[existing.partition] = existing }
        next[item.partition] = item
        return draft.copy(items = next.values.toList(), revision = draft.revision + 1L)
    }

    /** Move a visible row by one place without sorting the queue. */
    fun move(draft: FlashOperationDraft, partition: String, delta: Int): FlashOperationDraft {
        require(delta == -1 || delta == 1) { "Only adjacent moves are supported" }
        val list = draft.items.toMutableList()
        val original = list.indexOfFirst { it.partition == partition }
        if (original < 0) return draft
        val destination = original + delta
        if (destination !in list.indices) return draft
        val item = list.removeAt(original)
        list.add(destination, item)
        return draft.copy(items = list.toList(), revision = draft.revision + 1L)
    }

    fun remove(draft: FlashOperationDraft, partition: String): FlashOperationDraft {
        if (draft.items.none { it.partition == partition }) return draft
        return draft.copy(items = draft.items.filterNot { it.partition == partition }, revision = draft.revision + 1L)
    }

    fun clear(draft: FlashOperationDraft): FlashOperationDraft =
        draft.copy(items = emptyList(), revision = draft.revision + 1L)

    internal fun selectSlot(draft: FlashOperationDraft, slot: String?): FlashOperationDraft {
        require(slot == null || isSlotOverride(slot))
        return if (slot == draft.slot) draft else draft.copy(slot = slot, revision = draft.revision + 1L)
    }

    internal fun isSlotOverride(slot: String): Boolean = slot == "a" || slot == "b" || slot == "all"

    internal fun resolveTargets(
        partition: String,
        slot: String?,
        resolve: (String, String) -> List<String>?
    ): List<String>? {
        val name = partition.substringBefore(':')
        // Explicit A/B targets and combined firmware names such as xbl_ab are literal queue entries.
        return if (slot == null || name.endsWith("_a", true) || name.endsWith("_b", true) || name.endsWith("_ab", true)) {
            listOf(partition)
        } else {
            resolve(partition, slot)
        }
    }

    fun resolve(item: FlashQueueDraftItem): File? = runCatching {
        val uri = URI(item.sourceUri)
        if (!uri.scheme.equals("file", ignoreCase = true) || uri.authority != null) return null
        File(uri).canonicalFile.takeIf { it.exists() && it.isFile && it.canRead() && item.expectedSizeBytes > 0L && it.length() == item.expectedSizeBytes }
    }.getOrNull()

    fun isPersistable(item: FlashQueueDraftItem): Boolean =
        normalizePartition(item.partition) == item.partition &&
            item.sourceUri.isNotBlank() &&
            item.displayName.isNotBlank() &&
            item.expectedSizeBytes > 0L &&
            item.addedAtEpochMs >= 0L

    internal fun normalizePartition(value: String): String? {
        // Exact operator-supplied Fastboot identifiers are case-sensitive.
        val exact = value.trim()
        return exact.takeIf { PARTITION_PATTERN.matches(it) }
    }
}

/** Bundle/SavedStateHandle-safe codec for the lightweight queue. */
object FlashOperationDraftCodec {
    private const val SCHEMA = "2"
    private const val SLOT_FIELD = "slot"
    private const val SEPARATOR = '|'
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(draft: FlashOperationDraft): ArrayList<String> = ArrayList<String>().apply {
        // Optional metadata leaves the existing row format intact.
        draft.slot?.takeIf(FlashOperationDraftPolicy::isSlotOverride)?.let { add("$SLOT_FIELD$SEPARATOR$it") }
        draft.items.filter(FlashOperationDraftPolicy::isPersistable).mapTo(this) { item ->
            listOf(
                SCHEMA,
                encodeField(item.partition),
                encodeField(item.sourceUri),
                encodeField(item.displayName),
                item.expectedSizeBytes.toString(),
                item.addedAtEpochMs.toString()
            ).joinToString(SEPARATOR.toString())
        }
    }

    fun decode(encoded: List<String>?): FlashOperationDraft {
        if (encoded.isNullOrEmpty()) return FlashOperationDraft()
        val items = LinkedHashMap<String, FlashQueueDraftItem>()
        var slot: String? = null
        encoded.forEach { row ->
            val parts = row.split(SEPARATOR)
            if (parts.size == 2 && parts[0] == SLOT_FIELD) {
                if (FlashOperationDraftPolicy.isSlotOverride(parts[1])) slot = parts[1]
                return@forEach
            }
            if (parts.size != 6 || parts[0] != SCHEMA) return@forEach
            val item = runCatching {
                FlashQueueDraftItem(
                    partition = decodeField(parts[1]),
                    sourceUri = decodeField(parts[2]),
                    displayName = decodeField(parts[3]),
                    expectedSizeBytes = parts[4].toLong(),
                    addedAtEpochMs = parts[5].toLong()
                )
            }.getOrNull() ?: return@forEach
            if (FlashOperationDraftPolicy.isPersistable(item)) items[item.partition] = item
        }
        return FlashOperationDraft(items.values.toList(), slot = slot)
    }

    private fun encodeField(value: String): String =
        encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeField(value: String): String =
        decoder.decode(value).toString(Charsets.UTF_8)
}

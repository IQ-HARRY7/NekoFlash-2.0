package ru.forum.adbfastboottool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashOperationDraftPolicyTest {
    private fun item(target: String, name: String = target) = FlashQueueDraftItem(
        partition = target,
        sourceUri = "file:///tmp/$name.img",
        displayName = "$name.img",
        expectedSizeBytes = 1L,
        addedAtEpochMs = 1L
    )

    @Test fun replacementKeepsExistingPositionAndFileChanges() {
        val original = FlashOperationDraft(listOf(item("boot_a"), item("system"), item("dtbo")))
        val changed = FlashOperationDraftPolicy.upsert(original, item("system", "system_new"))
        assertEquals(listOf("boot_a", "system", "dtbo"), changed.items.map { it.partition })
        assertEquals("system_new.img", changed.items[1].displayName)
    }

    @Test fun exactSlotTargetsAreDistinctAndExecutionOrderRemainsManual() {
        val original = FlashOperationDraft(listOf(item("vendor_boot_b"), item("boot_a"), item("boot_b")))
        val updated = FlashOperationDraftPolicy.upsert(original, item("boot_a", "new_a"))
        assertEquals(listOf("vendor_boot_b", "boot_a", "boot_b"), updated.items.map { it.partition })
        assertEquals("new_a.img", updated.items[1].displayName)
        assertEquals("boot_b.img", updated.items[2].displayName)
    }

    @Test fun moveAndRemoveAreStable() {
        val original = FlashOperationDraft(listOf(item("system"), item("boot"), item("dtbo")))
        val moved = FlashOperationDraftPolicy.move(original, "boot", -1)
        assertEquals(listOf("boot", "system", "dtbo"), moved.items.map { it.partition })
        assertEquals(moved, FlashOperationDraftPolicy.move(moved, "boot", -1))
        assertEquals(listOf("boot", "dtbo"), FlashOperationDraftPolicy.remove(moved, "system").items.map { it.partition })
    }

    @Test fun manualPartitionPreservesExactCase() {
        val original = FlashOperationDraft(listOf(item("boot_a"), item("CustomPartition")))
        val newDraft = FlashOperationDraftPolicy.upsert(original, item("custompartition"))
        assertEquals(
            listOf("boot_a", "CustomPartition", "custompartition"),
            newDraft.items.map { it.partition }
        )
        assertEquals("CustomPartition", newDraft.items[1].partition)
    }

    @Test fun draftDoesNotSilentlyTruncateMetadata() {
        val longName = "X".repeat(420) + ".img"
        val longUri = "file:///tmp/" + "a".repeat(5000)
        val entry = FlashQueueDraftItem(
            partition = "Custom.Partition:raw",
            sourceUri = longUri,
            displayName = longName,
            expectedSizeBytes = 1L,
            addedAtEpochMs = 1L
        )
        assertTrue(FlashOperationDraftPolicy.isPersistable(entry))
        val restored = FlashOperationDraftCodec.decode(
            FlashOperationDraftCodec.encode(FlashOperationDraft(listOf(entry)))
        )
        assertEquals(longName, restored.items.single().displayName)
        assertEquals(longUri, restored.items.single().sourceUri)
        assertEquals("Custom.Partition:raw", restored.items.single().partition)
    }

    @Test fun codecDoesNotSilentlyDropEntriesAfterThirtyTwo() {
        val items = (0 until 65).map { item("partition_$it") }
        val encoded = FlashOperationDraftCodec.encode(FlashOperationDraft(items))
        assertEquals(65, encoded.size)
        val restored = FlashOperationDraftCodec.decode(encoded)
        assertEquals(items.map { it.partition }, restored.items.map { it.partition })
        assertTrue(restored.items.all { it.displayName.endsWith(".img") })
    }

    @Test fun legacyQueueStaysLiteralAndSlotSelectionSurvivesQueueEdits() {
        val original = FlashOperationDraft(listOf(item("boot"), item("recovery")))
        val encoded = FlashOperationDraftCodec.encode(original)
        assertNull(FlashOperationDraftCodec.decode(encoded).slot)
        assertEquals(listOf("boot"), FlashOperationDraftPolicy.resolveTargets("boot", null) { _, _ ->
            error("The original queue sends the listed name directly")
        })

        val selected = FlashOperationDraftPolicy.selectSlot(original, "all")
        val updated = FlashOperationDraftPolicy.upsert(selected, item("dtbo"))
        val moved = FlashOperationDraftPolicy.move(updated, "dtbo", -1)
        val edited = FlashOperationDraftPolicy.remove(moved, "recovery")
        val restored = FlashOperationDraftCodec.decode(FlashOperationDraftCodec.encode(edited))
        assertEquals("all", restored.slot)
        assertEquals(listOf("boot", "dtbo"), restored.items.map { it.partition })
        assertNull(FlashOperationDraftPolicy.selectSlot(restored, null).slot)
    }

    @Test fun allPassesBaseNamesToFastbootAndKeepsExplicitNamesInPlace() {
        val observed = mutableListOf<Pair<String, String>>()
        val queue = listOf("boot", "recovery", "dtbo_b", "Vendor_B:ramdisk", "xbl_ab")
        val resolved = queue.flatMap { partition ->
            requireNotNull(FlashOperationDraftPolicy.resolveTargets(partition, "all") { base, slot ->
                observed += base to slot
                if (base == "boot") listOf("boot_a", "boot_b") else listOf(base)
            })
        }
        assertEquals(listOf("boot" to "all", "recovery" to "all"), observed)
        assertEquals(listOf("boot_a", "boot_b", "recovery", "dtbo_b", "Vendor_B:ramdisk", "xbl_ab"), resolved)
    }

    @Test fun explicitAOrBIsPassedThroughToFastboot() {
        for (slot in listOf("a", "b")) {
            val target = FlashOperationDraftPolicy.resolveTargets("boot", slot) { partition, selected ->
                listOf("${partition}_$selected")
            }
            assertEquals(listOf("boot_$slot"), target)
        }
    }

    @Test fun changedSourceSizeRequiresReselection() {
        val file = java.nio.file.Files.createTempFile("queue", ".img").toFile()
        try {
            file.writeText("original")
            val entry = item("boot").copy(sourceUri = file.toURI().toString(), expectedSizeBytes = file.length())
            assertEquals(file.canonicalFile, FlashOperationDraftPolicy.resolve(entry))
            file.appendText("changed")
            org.junit.Assert.assertNull(FlashOperationDraftPolicy.resolve(entry))
        } finally { file.delete() }
    }
}

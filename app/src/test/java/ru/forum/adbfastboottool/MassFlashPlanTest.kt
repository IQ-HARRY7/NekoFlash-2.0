package ru.forum.adbfastboottool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MassFlashPlanTest {
    @Test fun expandsOnlySlottedPartitionsInPlaceAndKeepsRepeatedTargets() {
        val draft = draft("boot", "recovery", "boot_a", "xbl_ab", slot = "all")
        val probes = mutableListOf<String>()
        val plan = MassFlashPlan.resolve(draft) { partition, slot ->
            assertEquals("all", slot)
            probes += partition
            if (partition == "boot") listOf("boot_a", "boot_b") else listOf(partition)
        }

        assertEquals(listOf("boot", "recovery"), probes)
        assertEquals(listOf("boot_a", "boot_b", "recovery", "boot_a", "xbl_ab"), plan.map { it.target })
        assertEquals(listOf("boot.img", "boot.img", "recovery.img", "boot_a.img", "xbl_ab.img"),
            plan.map { it.source.displayName })
        assertEquals(setOf("boot_a"), MassFlashPlan.repeatedTargets(plan))
    }

    @Test fun leavesUnknownRowsInPlaceAndShowsLiteralTargetsOffline() {
        val draft = draft("system", "boot_b", "xbl_ab", slot = "a")
        val plan = MassFlashPlan.resolve(draft) { _, _ -> null }
        assertEquals(listOf(null, "boot_b", "xbl_ab"), plan.map { it.target })
        assertEquals(draft.items, plan.map { it.source })
        assertTrue(MassFlashPlan.repeatedTargets(plan).isEmpty())

        val asListed = MassFlashPlan.resolve(draft.copy(slot = null)) { _, _ ->
            error("Literal queue unexpectedly queried a device")
        }
        assertEquals(listOf("system", "boot_b", "xbl_ab"), asListed.map { it.target })
    }

    private fun draft(vararg partitions: String, slot: String?) = FlashOperationDraft(
        items = partitions.map { FlashQueueDraftItem(it, "file:///$it.img", "$it.img", 1024L, 0L) },
        slot = slot
    )
}

package ru.forum.adbfastboottool

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class MassFlashQueueUiTest {
    class Screen : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_NekoFlash)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.page_fastboot)
        }
    }

    @Test fun formInflatesWithRecyclerLayoutParamsBeforeFirstRender() {
        val controller = Robolectric.buildActivity(Screen::class.java).setup()
        try {
            val activity = controller.get()
            val ui = MassFlashQueueUi(
                activity, { _, _ -> fail("Unexpected move") }, { fail("Unexpected remove") },
                { fail("Unexpected slot change") }
            )
            ui.render(FlashOperationDraft(), QueueSourceState())
            assertTrue(ui.header.layoutParams is RecyclerView.LayoutParams)
            assertEquals(1, activity.findViewById<RecyclerView>(R.id.rvMassQueue).adapter!!.itemCount)
            assertFalse(ui.execute.isEnabled)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun queueSlotChooserDisplaysSavedSlotAndSelectsAllSlots() {
        val controller = Robolectric.buildActivity(Screen::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<View>(R.id.fastbootMassSection).visibility = View.VISIBLE
            val selections = mutableListOf<String?>()
            val ui = MassFlashQueueUi(activity, { _, _ -> }, { }, { selections.add(it) })
            ui.render(FlashOperationDraft(slot = "b"), QueueSourceState())
            val slotButton = activity.findViewById<MaterialButton>(R.id.btnMassSlot)
            assertEquals(activity.getString(R.string.mass_flash_slot_value, "B"), slotButton.text.toString())

            assertTrue(slotButton.performClick())
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val choices = requireNotNull(dialog.listView)
            assertEquals(2, choices.checkedItemPosition)
            val allSlots = choices.adapter.getView(3, null, choices)
            assertTrue(choices.performItemClick(allSlots, 3, choices.adapter.getItemId(3)))
            assertEquals(listOf("all"), selections)
            assertFalse(dialog.isShowing)

            ui.render(FlashOperationDraft(slot = "all"), QueueSourceState())
            val allLabel = activity.getString(R.string.flash_rev7_slot_all)
            assertEquals(activity.getString(R.string.mass_flash_slot_value, allLabel), slotButton.text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "land")
    fun landscapeQueueShowsThePreviewControlForASelectedImage() {
        val controller = Robolectric.buildActivity(Screen::class.java).setup()
        try {
            val ui = MassFlashQueueUi(controller.get(), { _, _ -> }, {}, {})
            ui.render(FlashOperationDraft(), QueueSourceState())
            assertEquals(View.GONE, ui.preview.visibility)

            val image = FlashQueueDraftItem("recovery", "file:///tmp/recovery.img", "recovery.img", 1L, 1L)
            ui.render(FlashOperationDraft(listOf(image)), QueueSourceState())
            assertEquals(View.VISIBLE, ui.preview.visibility)
        } finally { controller.pause().stop().destroy() }
    }
}

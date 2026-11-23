package ru.forum.adbfastboottool

import android.view.View
import android.widget.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickFlashSlotUiTest {
    @Test fun shortcutForwardsCurrentAndAllWithoutPinningACachedSlot() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get().apply {
            setTheme(R.style.Theme_NekoFlash)
        }
        val showChooser = MainActivity::class.java.getDeclaredMethod(
            "showQuickFlashSlotDialog", String::class.java, Int::class.javaPrimitiveType, Function1::class.java
        ).apply { isAccessible = true }
        val choices = mutableListOf<String?>()
        val onChosen: (String?) -> Unit = { choices.add(it) }

        for (label in listOf(
            activity.getString(R.string.quick_flash_active_slot_target),
            activity.getString(R.string.quick_flash_all_slots_range, "b")
        )) {
            showChooser.invoke(activity, "boot", 2, onChosen)
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            try {
                val matches = ArrayList<View>()
                requireNotNull(dialog.window).decorView.findViewsWithText(matches, label, View.FIND_VIEWS_WITH_TEXT)
                val button = matches.filterIsInstance<Button>().single { it.text.toString() == label }
                assertTrue(button.performClick())
            } finally {
                dialog.dismiss()
            }
        }

        assertEquals(listOf(null, "all"), choices)
    }
}

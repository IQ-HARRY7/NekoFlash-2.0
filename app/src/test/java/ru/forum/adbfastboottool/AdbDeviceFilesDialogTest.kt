package ru.forum.adbfastboottool

import android.os.Bundle
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class AdbDeviceFilesDialogTest {
    class Screen : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_NekoFlash)
            super.onCreate(savedInstanceState)
        }
    }

    @Test fun aLateListingDoesNotOverwriteTheCurrentDirectory() {
        val controller = Robolectric.buildActivity(Screen::class.java).setup()
        try {
            var olderRequest: Continuation<List<AdbDeviceDirectory.Entry>?>? = null
            val browser = AdbDeviceFilesDialog(
                activity = controller.get(),
                initialDirectory = "/sdcard",
                list = { path ->
                    when (path) {
                        "/sdcard/older" -> suspendCoroutine { olderRequest = it }
                        "/sdcard/newer" -> listOf(AdbDeviceDirectory.Entry("fresh.txt", false))
                        else -> emptyList()
                    }
                },
                pull = { false },
                send = {}
            )
            browser.show()
            shadowOf(Looper.getMainLooper()).idle()

            // Simulate two queued navigation requests whose ADB responses arrive out of order.
            val open = AdbDeviceFilesDialog::class.java.getDeclaredMethod("open", String::class.java)
            open.isAccessible = true
            open.invoke(browser, "/sdcard/older")
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(olderRequest)

            open.invoke(browser, "/sdcard/newer")
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals("fresh.txt", dialog.listView.adapter.getItem(0).toString())

            olderRequest!!.resume(listOf(AdbDeviceDirectory.Entry("stale.txt", false)))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("fresh.txt", dialog.listView.adapter.getItem(0).toString())
            browser.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }
}

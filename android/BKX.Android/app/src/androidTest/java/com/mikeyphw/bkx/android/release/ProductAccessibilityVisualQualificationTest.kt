package com.mikeyphw.bkx.android.release

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.mikeyphw.bkx.android.app.MainActivity
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test

/** AOV-28/AOV-31 accessibility semantics plus opt-in real-device visual evidence. */
class ProductAccessibilityVisualQualificationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun primaryProductNavigationIsAccessibleAndCanCaptureQualificationScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val visualQualification = InstrumentationRegistry.getArguments()
            .getString(ARG_VISUAL_QUALIFICATION)
            ?.equals("true", ignoreCase = true) == true

        val destinations = listOf("home", "backup", "archives", "activity", "more")
        destinations.forEach { destination ->
            val node = composeRule.onNodeWithTag("nav-$destination", useUnmergedTree = true)
            node.assertExists().assertHasClickAction().assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            if (visualQualification) {
                val outputDir = File(instrumentation.targetContext.filesDir, SCREENSHOT_DIRECTORY).apply { mkdirs() }
                val output = File(outputDir, "$destination.png")
                val bitmap = composeRule.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
                FileOutputStream(output).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Could not encode $destination qualification screenshot." }
                    stream.fd.sync()
                }
                check(output.isFile && output.length() > 0L) { "Qualification screenshot was not written: $output" }
            }
        }

    }

    private companion object {
        const val ARG_VISUAL_QUALIFICATION = "bkxVisualQualification"
        const val SCREENSHOT_DIRECTORY = "qualification-screenshots"
    }
}

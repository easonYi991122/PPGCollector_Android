package com.example.ppgcollector_android

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = PpgCollectorApplication::class)
class ReviewAdaptiveLayoutTest {
    @Test fun narrowLandscapeRangeControlsKeepTwoColumnsAndTouchTargets() {
        listOf(640f, 800f).forEach { width ->
            assertEquals(2, ReviewAdaptiveLayoutPolicy.rangeColumns)
            assertTrue(ReviewAdaptiveLayoutPolicy.rangeButtonWidthDp(width) >= 58)
            assertTrue(ReviewAdaptiveLayoutPolicy.rangeButtonMinimumHeightDp >= 48)
        }
    }

    @Test fun scaledCompactTilesUseThreePlusTwoAndRetainPressureSeverity() {
        assertEquals(3, CaptureUiPolicy.compactMetricColumns(300f, 1.3f))
        assertEquals(5, CaptureUiPolicy.compactMetricColumns(450f, 1.3f))
        assertEquals("⚠ 压力过大(严重)", compactMetricCaption("⚠ 压力过大(严重) (0.27)"))
        for (scale in listOf(1f, 1.3f, 2f)) {
            val columns = CaptureUiPolicy.compactMetricColumns(300f, scale)
            assertTrue((300f - (columns - 1) * 5) / columns - 12 >= 54 * scale)
        }
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun productionCaptionStyleMeasuresCompleteSeverePressureAtScaledTileWidth() {
        val widthDp = 300f
        val fontScale = 1.3f
        val columns = CaptureUiPolicy.compactMetricColumns(widthDp, fontScale)
        val textWidth = (widthDp - (columns - 1) * 5) / columns - 12
        val text = compactMetricCaption("⚠ 压力过大(严重) (0.27)")
        val paragraph = androidx.compose.ui.text.Paragraph(
            text = text,
            style = compactMetricCaptionStyle(androidx.compose.ui.text.TextStyle.Default),
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = textWidth.toInt()),
            density = androidx.compose.ui.unit.Density(1f, fontScale),
            fontFamilyResolver = androidx.compose.ui.text.font.createFontFamilyResolver(org.robolectric.RuntimeEnvironment.getApplication()),
        )
        assertFalse(paragraph.didExceedMaxLines)
        assertEquals(text.length, paragraph.getLineEnd(paragraph.lineCount - 1, visibleEnd = true))
        assertTrue(paragraph.height >= 12 * fontScale)
    }

}

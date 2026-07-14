package fit

import kotlin.test.Test
import kotlin.test.assertEquals

class LetterboxTransformerTest {
    @Test
    fun testLetterboxCalculationsAndCoordinatesMapping() {
        // 16:9 input (1280x720) downscaled to 640x640 letterbox target
        val transformer = LetterboxTransformer(1280f, 720f, 640f)

        // Scale should be 0.5f (since 640/1280 = 0.5 < 640/720)
        assertEquals(0.5f, transformer.scale, 0.0001f)
        assertEquals(640f, transformer.newW, 0.0001f)
        assertEquals(360f, transformer.newH, 0.0001f)
        assertEquals(0f, transformer.offsetX, 0.0001f)
        assertEquals(140f, transformer.offsetY, 0.0001f) // (640 - 360) / 2 = 140

        // Test coordinate mapping from letterbox target back to source
        val box = transformer.mapToSourceBox(270f, 270f, 370f, 370f)
        assertEquals(540, box.x1)
        assertEquals(260, box.y1)
        assertEquals(740, box.x2)
        assertEquals(460, box.y2)
    }

    @Test
    fun testVerticalImageLetterboxing() {
        // Vertical image (1080x1920) downscaled to 1088x1088 target
        val transformer = LetterboxTransformer(1080f, 1920f, 1088f)

        // Scale should be 1088/1920 = 0.56666f
        val expectedScale = 1088f / 1920f
        assertEquals(expectedScale, transformer.scale, 0.0001f)
        assertEquals(1088f, transformer.newH, 0.0001f)
        assertEquals(1080f * expectedScale, transformer.newW, 0.0001f)
        assertEquals(0f, transformer.offsetY, 0.0001f)
        assertEquals((1088f - (1080f * expectedScale)) / 2f, transformer.offsetX, 0.0001f)

        // Ensure round-trip mapping coordinates are mathematically correct and symmetric
        val srcX = 500f
        val srcY = 1000f
        val tx = transformer.toTargetX(srcX)
        val ty = transformer.toTargetY(srcY)
        
        assertEquals(srcX, transformer.toSourceX(tx), 0.01f)
        assertEquals(srcY, transformer.toSourceY(ty), 0.01f)
    }

    @Test
    fun testSquareImageLetterboxing() {
        // Square image (1000x1000) downscaled to 1088x1088 target
        val transformer = LetterboxTransformer(1000f, 1000f, 1088f)

        assertEquals(1.088f, transformer.scale, 0.0001f)
        assertEquals(1088f, transformer.newW, 0.0001f)
        assertEquals(1088f, transformer.newH, 0.0001f)
        assertEquals(0f, transformer.offsetX, 0.0001f)
        assertEquals(0f, transformer.offsetY, 0.0001f)

        // Mapping validation
        val box = transformer.mapToSourceBox(108.8f, 108.8f, 217.6f, 217.6f)
        assertEquals(100, box.x1)
        assertEquals(100, box.y1)
        assertEquals(200, box.x2)
        assertEquals(200, box.y2)
    }
}

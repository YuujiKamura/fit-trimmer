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
        // tx1 = 270, ty1 = 270, tx2 = 370, ty2 = 370
        val box = transformer.mapToSourceBox(270f, 270f, 370f, 370f)

        // Expected x1: (270 - 0) / 0.5 = 540
        // Expected y1: (270 - 140) / 0.5 = 260
        // Expected x2: (370 - 0) / 0.5 = 740
        // Expected y2: (370 - 140) / 0.5 = 460
        assertEquals(540, box.x1)
        assertEquals(260, box.y1)
        assertEquals(740, box.x2)
        assertEquals(460, box.y2)
    }
}

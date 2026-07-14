package fit

class LetterboxTransformer(
    val sourceWidth: Float,
    val sourceHeight: Float,
    val targetSize: Float
) {
    val scale = kotlin.math.min(targetSize / sourceWidth.coerceAtLeast(1f), targetSize / sourceHeight.coerceAtLeast(1f))
    val newW = sourceWidth * scale
    val newH = sourceHeight * scale
    val offsetX = (targetSize - newW) / 2f
    val offsetY = (targetSize - newH) / 2f

    // to target coordinate conversions
    fun toTargetX(srcX: Float): Float = srcX * scale + offsetX
    fun toTargetY(srcY: Float): Float = srcY * scale + offsetY

    // to source coordinate conversions
    fun toSourceX(targetX: Float): Float = ((targetX - offsetX) / scale).coerceIn(0f, sourceWidth)
    fun toSourceY(targetY: Float): Float = ((targetY - offsetY) / scale).coerceIn(0f, sourceHeight)

    // map target box back to PlateBox in original coordinates
    fun mapToSourceBox(tx1: Float, ty1: Float, tx2: Float, ty2: Float): PlateBox {
        return PlateBox(
            x1 = toSourceX(tx1).toInt(),
            y1 = toSourceY(ty1).toInt(),
            x2 = toSourceX(tx2).toInt(),
            y2 = toSourceY(ty2).toInt()
        )
    }
}

package tribixbite.cleverkeys.theme

/**
 * A still picture of a look's backdrop for Theme Manager's cards (maintainer 2026-11-12: "add
 * true black to the theme picker previews"): the painted scene at rest, or the aurora's
 * curtains over its base, in true black when [trueBlack] is set, exactly as the keyboard paints
 * them ([SceneRaster], [SceneTrueBlack], [AuroraRaster]). Pure JVM: `ScenePreviewTest`.
 */
object ScenePreview {

    /**
     * Opaque ARGB pixels, [width] × [height], of [backdrop] over [baseColor] at [density] raster px
     * per dp; null when the backdrop paints nothing (no scene and no curtains) or the size is empty.
     */
    fun pixels(backdrop: LookBackdrop, baseColor: Int, width: Int, height: Int, density: Float, trueBlack: Boolean): IntArray? {
        if (width <= 0 || height <= 0) return null
        val scene = backdrop.scene
        if (scene != null) {
            val painted = SceneRaster.paint(scene, width, height, density)
            val shown = if (trueBlack) SceneTrueBlack.apply(painted, scene, density) else painted
            return SceneMotion.composite(shown, 0.0)
        }
        if (backdrop.ribbons.isEmpty()) return null
        // The aurora in true black: the same curtains over a black sky (BackdropDrawable.paintKey).
        val base = if (trueBlack) 0xFF000000.toInt() else (baseColor or 0xFF000000.toInt())
        return AuroraRaster.render(base, backdrop.ribbons, width, height, density)
    }
}

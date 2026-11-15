package io.github.lemyst.enfusion.objfmt.preview

import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * What an `ImageWidgetClass` does to its picture before drawing it, as Workbench shows it. Plain
 * pixels, no IntelliJ API, so it is tested on made-up images.
 *
 * - `color` multiplies every pixel, alpha included: vanilla tints one white dial grey in one widget
 *   and red in another (`day_z_hud_cars.layout`), and darkens a white vignette to 78 % black.
 * - `Mask` with `Progress` keeps the pixels whose mask value (red channel, 0 to 1) is below the
 *   progress. The car HUD's dial masks are an angular gradient, dark where the dial starts and light
 *   where it ends, and `Progress 0.8` draws 80 % of the arc, as Workbench does. A `Transition width`
 *   fades the edge over that much of the mask's range, the edge sweeping from `-width` to 1 as
 *   the progress goes from 0 to 1, so that 0 shows nothing and 1 everything. That sweep is a guess
 *   (`loading.layout` fades its background in with width 1 from progress 0).
 * - `flip u` / `flip v` mirror the result, mask included: the red-line widget flips the same dial
 *   and mask so that its `Progress 0.2` covers the end of the arc rather than its start.
 */
object LayoutImages {

    fun finish(picture: BufferedImage, spec: WidgetSpec, mask: BufferedImage?): BufferedImage {
        val width = picture.width
        val height = picture.height
        val pixels = picture.getRGB(0, 0, width, height, null, 0, width)
        val maskPixels = mask?.let { scaled(it, width, height).getRGB(0, 0, width, height, null, 0, width) }
        val tint = spec.color ?: -1
        val ta = tint ushr 24 and 0xFF
        val tr = tint ushr 16 and 0xFF
        val tg = tint ushr 8 and 0xFF
        val tb = tint and 0xFF
        val progress = spec.number("progress", 0, 1.0)
        val transition = spec.number("transition width", 0, 0.0)
        for (i in pixels.indices) {
            val p = pixels[i]
            var a = (p ushr 24 and 0xFF) * ta / 255
            if (maskPixels != null) a = (a * shown((maskPixels[i] ushr 16 and 0xFF) / 255.0, progress, transition)).toInt()
            pixels[i] = (a shl 24) or
                ((p ushr 16 and 0xFF) * tr / 255 shl 16) or
                ((p ushr 8 and 0xFF) * tg / 255 shl 8) or
                ((p and 0xFF) * tb / 255)
        }
        val flipU = spec.flag("flip u", false)
        val flipV = spec.flag("flip v", false)
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        if (!flipU && !flipV) {
            out.setRGB(0, 0, width, height, pixels, 0, width)
            return out
        }
        val flipped = IntArray(pixels.size)
        for (y in 0 until height) {
            val from = if (flipV) height - 1 - y else y
            for (x in 0 until width) {
                flipped[y * width + x] = pixels[from * width + if (flipU) width - 1 - x else x]
            }
        }
        out.setRGB(0, 0, width, height, flipped, 0, width)
        return out
    }

    /** How much of a pixel with mask value [m] shows at [progress]: all or nothing, or a ramp [transition] wide. */
    internal fun shown(m: Double, progress: Double, transition: Double): Double =
        if (transition <= 0.0) {
            if (m < progress) 1.0 else 0.0
        } else {
            ((progress * (1 + transition) - m) / transition).coerceIn(0.0, 1.0)
        }

    private fun scaled(image: BufferedImage, width: Int, height: Int): BufferedImage {
        if (image.width == width && image.height == height) return image
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(image, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        return out
    }
}

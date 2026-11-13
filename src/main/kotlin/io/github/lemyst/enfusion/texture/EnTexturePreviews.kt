package io.github.lemyst.enfusion.texture

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import io.github.lemyst.enfusion.texture.format.Texture
import io.github.lemyst.enfusion.texture.format.TextureFormatException
import io.github.lemyst.enfusion.texture.format.TextureReader
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.IOException
import java.lang.ref.SoftReference

/**
 * Small decoded previews of whole textures and of imageset entries, cached.
 *
 * Three caches, because the three costs differ. A header is cheap but needs the file read. A
 * decoded mip is the expensive part, and the entries of one imageset are all crops of the *same*
 * atlas mip, so a few of them are kept. A finished thumbnail is tiny and asked for again every time
 * the same reference is hovered, so many are kept. Every key includes the file's modification
 * stamp, so a re-exported texture is never shown stale.
 *
 * Failures are cached too. A corrupt or BC7 texture would otherwise be read and rejected again on
 * every hover.
 */
object EnTexturePreviews {

    /** What a preview says about the texture it came from. */
    class Summary(
        val container: String,
        val format: String,
        val width: Int,
        val height: Int,
        val mipCount: Int,
        val compression: String,
    ) {
        fun describe(): String =
            "$width × $height · $format · $mipCount mip${if (mipCount == 1) "" else "s"} · $container"
    }

    sealed class Preview {
        class Image(val image: BufferedImage, val summary: Summary) : Preview()
        class Failure(val message: String) : Preview()
    }

    /**
     * A rectangle of a texture, in the coordinates its imageset uses: `Pos` and `Size` are in
     * `RefSize` units, not in texture pixels, because one imageset can point to a 1x and a 2x
     * texture. Without a `RefSize`, they are pixels of the top mip.
     */
    data class Region(val x: Int, val y: Int, val width: Int, val height: Int, val refWidth: Int?, val refHeight: Int?) {

        /**
         * The rectangle as fractions of a texture whose largest mip is [topWidth] × [topHeight], or
         * null when it lies outside it. `Pos` and `Size` count in a [refWidth] × [refHeight] space
         * (see [EnImageSetTextures]), so fractions place the same rectangle on the 1x and the 2x
         * texture alike, and on any mip of either. With no space known, they count in the
         * texture's own pixels.
         */
        fun fractions(topWidth: Int, topHeight: Int): Fractions? {
            val refW = (refWidth ?: topWidth).coerceAtLeast(1).toDouble()
            val refH = (refHeight ?: topHeight).coerceAtLeast(1).toDouble()
            val f = Fractions(x / refW, y / refH, width / refW, height / refH)
            return f.takeUnless { it.w <= 0 || it.h <= 0 || it.x < 0 || it.y < 0 || it.x + it.w > 1.0001 || it.y + it.h > 1.0001 }
        }
    }

    data class Fractions(val x: Double, val y: Double, val w: Double, val h: Double)

    fun ofTexture(file: VirtualFile, maxSide: Int): Preview = preview(file, null, maxSide)

    /**
     * The size of the texture's largest mip, or null when it cannot be read. Cached with the
     * preview headers, so asking again for an unchanged file reads nothing.
     */
    fun dimensions(file: VirtualFile): Pair<Int, Int>? {
        val fileKey = FileKey(file.url, file.modificationStamp)
        headers[fileKey]?.let { return it.mipSizes[0] }
        return try {
            headerOf(load(file)).also { headers[fileKey] = it }.mipSizes[0]
        } catch (e: TextureFormatException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    private fun headerOf(t: Texture) =
        Header(Summary(t.container, t.formatName, t.width, t.height, t.mips.size, t.compression), t.mips.map { it.width to it.height })

    fun ofRegion(file: VirtualFile, region: Region, maxSide: Int): Preview = preview(file, region, maxSide)

    private data class FileKey(val url: String, val stamp: Long)
    private data class LevelKey(val file: FileKey, val level: Int)
    private data class ThumbKey(val file: FileKey, val region: Region?, val maxSide: Int)

    private class Header(val summary: Summary, val mipSizes: List<Pair<Int, Int>>)

    private val headers = Lru<FileKey, Header>(256)
    /** Soft: a 2048² atlas mip is 16 MB, and the heap is better spent elsewhere under pressure. */
    private val levels = Lru<LevelKey, SoftReference<BufferedImage>>(8)
    private val thumbnails = Lru<ThumbKey, Preview>(512)

    private fun preview(file: VirtualFile, region: Region?, maxSide: Int): Preview {
        val fileKey = FileKey(file.url, file.modificationStamp)
        val thumbKey = ThumbKey(fileKey, region, maxSide)
        thumbnails[thumbKey]?.let { return it }
        val result = try {
            render(file, fileKey, region, maxSide)
        } catch (e: TextureFormatException) {
            Preview.Failure(e.message ?: "unreadable texture")
        } catch (e: IOException) {
            // Not cached: a read error is usually transient (a file being re-exported).
            return Preview.Failure("cannot read ${file.name}: ${e.message}")
        }
        thumbnails[thumbKey] = result
        return result
    }

    private fun render(file: VirtualFile, fileKey: FileKey, region: Region?, maxSide: Int): Preview {
        var texture: Texture? = null
        fun texture(): Texture = texture ?: load(file).also { texture = it }

        val header = headers[fileKey] ?: headerOf(texture()).also { headers[fileKey] = it }
        val (topWidth, topHeight) = header.mipSizes[0]

        // The rectangle as fractions of the texture, so it can be cut from any mip.
        val (fx, fy, fw, fh) = if (region == null) Fractions(0.0, 0.0, 1.0, 1.0)
        else region.fractions(topWidth, topHeight) ?: return Preview.Failure("the image lies outside its texture")

        // The smallest mip in which the rectangle still covers the preview: detail without decoding
        // a 4096² mip for a 16-pixel icon.
        var level = 0
        for (i in header.mipSizes.indices.reversed()) {
            val (w, h) = header.mipSizes[i]
            if (fw * w >= maxSide || fh * h >= maxSide) {
                level = i
                break
            }
        }

        ProgressManager.checkCanceled()
        val levelKey = LevelKey(fileKey, level)
        val mip = levels[levelKey]?.get() ?: texture().decode(level).also { levels[levelKey] = SoftReference(it) }

        val x = (fx * mip.width).toInt().coerceIn(0, mip.width - 1)
        val y = (fy * mip.height).toInt().coerceIn(0, mip.height - 1)
        val w = Math.round(fw * mip.width).toInt().coerceIn(1, mip.width - x)
        val h = Math.round(fh * mip.height).toInt().coerceIn(1, mip.height - y)
        return Preview.Image(scaleToFit(mip, x, y, w, h, maxSide), header.summary)
    }

    private fun load(file: VirtualFile): Texture {
        if (file.length > TextureReader.MAX_FILE_BYTES) throw TextureFormatException("${file.name} is too large to preview")
        return TextureReader.read(file.contentsToByteArray())
    }

    /**
     * Fits a crop into [maxSide]. Shrinking is bilinear, which is enough because the mip was picked
     * to be at most about twice the target. Tiny images (a 4×4 crosshair dot) are enlarged by a whole
     * factor with no smoothing, so their pixels stay legible instead of turning to blur.
     */
    private fun scaleToFit(src: BufferedImage, x: Int, y: Int, w: Int, h: Int, maxSide: Int): BufferedImage {
        val scale = maxSide.toDouble() / maxOf(w, h)
        val (outW, outH, smooth) = if (scale >= 1) {
            val factor = scale.toInt().coerceIn(1, 8)
            Triple(w * factor, h * factor, false)
        } else {
            Triple(maxOf(1, Math.round(w * scale).toInt()), maxOf(1, Math.round(h * scale).toInt()), true)
        }
        val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (smooth) RenderingHints.VALUE_INTERPOLATION_BILINEAR else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
            )
            g.drawImage(src, 0, 0, outW, outH, x, y, x + w, y + h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /** A synchronized LRU map: these caches are read from highlighting and documentation threads alike. */
    private class Lru<K, V>(private val capacity: Int) {
        private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
        }

        @Synchronized
        operator fun get(key: K): V? = map[key]

        @Synchronized
        operator fun set(key: K, value: V) {
            map[key] = value
        }
    }
}

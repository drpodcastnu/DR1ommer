package ommer.graphics

import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

private val log = LoggerFactory.getLogger("ommer.graphics")!!

/** Large enough to stay sharp at the 120px the index page shows it at on a 2x display. */
private const val thumbnailSize = 240

fun generatePodcastImage(
    imageFile: File,
    thumbnailFile: File,
    colorStart: String,
    colorEnd: String,
    text: String,
) {
    val image = createGradientImageWithText(
        width = 1400,
        height = 1400,
        colorStart = Color.decode(colorStart),
        colorEnd = Color.decode(colorEnd),
        text = text,
        textColor = Color.WHITE,
        borderColor = Color.BLACK,
        fontSize = 96,
    )
    writeImageToFile(image, imageFile, 1.0f)
    writeImageToFile(scaleTo(image, thumbnailSize), thumbnailFile, 0.85f)
}

/**
 * Halve repeatedly before the final step. Going from 1400px to 240px in one pass samples too few
 * source pixels and aliases the text badly.
 */
private fun scaleTo(source: BufferedImage, size: Int): BufferedImage {
    var current = source
    while (current.width / 2 > size) current = redraw(current, current.width / 2)
    return redraw(current, size)
}

private fun redraw(source: BufferedImage, size: Int): BufferedImage {
    val target = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val graphics = target.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    graphics.drawImage(source, 0, 0, size, size, null)
    graphics.dispose()
    return target
}

private fun writeImageToFile(image: BufferedImage, imageFile: File, quality: Float) {
    // An image output stream opens the file read-write without truncating, so a shorter image than
    // the one already there would leave the old tail behind.
    imageFile.delete()
    val writer = ImageIO.getImageWritersByFormatName("jpg").next()
    val writeParam = writer.defaultWriteParam
    writeParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
    writeParam.compressionQuality = quality
    ImageIO.createImageOutputStream(imageFile).use { output ->
        writer.output = output
        writer.write(null, IIOImage(image, null, null), writeParam)
    }
}

private fun createGradientImageWithText(
    width: Int,
    height: Int,
    colorStart: Color,
    colorEnd: Color,
    text: String,
    textColor: Color,
    borderColor: Color,
    fontSize: Int,
): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    val paint = GradientPaint(0f, 0f, colorStart, width.toFloat(), height.toFloat(), colorEnd)
    graphics.paint = paint
    graphics.fillRect(0, 0, width, height)

    drawText(
        null,
        graphics,
        text,
        { rect, _ -> ((width - rect.width) / 2).toFloat() },
        { rect, fontMetrics -> ((height - rect.height) / 2 + fontMetrics.ascent).toFloat() },
        borderColor,
        textColor,
        width,
    )
    drawText(
        fontSize,
        graphics,
        "Fra DR Lyd",
        { rect, _ -> (width - rect.width - 8).toFloat() },
        { rect, fontMetrics -> (height - fontMetrics.maxDescent - 8).toFloat() },
        borderColor,
        textColor,
        width,
    )
    drawText(
        32,
        graphics,
        "drpodcast.nu",
        { rect, _ -> 8.toFloat() },
        { _, fontMetrics -> (fontMetrics.maxAscent + 8).toFloat() },
        borderColor,
        textColor,
        width,
    )

    graphics.dispose()
    return image
}

private fun calculateFontSize(
    graphics: Graphics2D,
    text: String,
    width: Int,
): Int {
    var current = 256
    while (current > 1) {
        graphics.font = Font("Arial", Font.BOLD, current)
        if (graphics.fontMetrics.getStringBounds(text, graphics).width <= width) return current
        current--
    }
    log.error("Could not determine font size for text '$text'; defaulting to 32")
    return 32
}

private fun drawText(
    fontSize: Int?,
    graphics: Graphics2D,
    text: String,
    xPos: (Rectangle2D, FontMetrics) -> Float,
    yPos: (Rectangle2D, FontMetrics) -> Float,
    borderColor: Color,
    textColor: Color,
    width: Int,
) {
    val effectiveFontSize = fontSize ?: calculateFontSize(graphics, text, width)
    graphics.font = Font("Arial", Font.BOLD, effectiveFontSize)
    val fontMetrics = graphics.fontMetrics
    val rect = fontMetrics.getStringBounds(text, graphics)
    val x = xPos(rect, fontMetrics)
    val y = yPos(rect, fontMetrics)
    graphics.color = borderColor
    graphics.fillRect(x.toInt(), (y + fontMetrics.maxDescent - rect.height) .toInt(), rect.width.toInt(), rect.height.toInt())
    graphics.color = textColor
    graphics.drawString(text, x, y)
}
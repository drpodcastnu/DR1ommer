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

fun generatePodcastImage(
    imageFile: File,
    colorStart: String,
    colorEnd: String,
    text: String,
) {
    val image = createGradientImageWithText(
        width = 1024,
        height = 1024,
        colorStart = Color.decode(colorStart),
        colorEnd = Color.decode(colorEnd),
        text = text,
        textColor = Color.WHITE,
        borderColor = Color.BLACK,
        fontSize = 96,
    )
    writeImageToFile(image, imageFile)
}

private fun writeImageToFile(image: BufferedImage, imageFile: File) {
    val writer = ImageIO.getImageWritersByFormatName("jpg").next()
    val writeParam = writer.defaultWriteParam
    writeParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
    writeParam.compressionQuality = 1.0f // Highest quality
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
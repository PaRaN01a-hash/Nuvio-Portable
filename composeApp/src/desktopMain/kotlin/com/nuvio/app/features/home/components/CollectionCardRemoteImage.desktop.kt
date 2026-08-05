package com.nuvio.app.features.home.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.max

private data class DesktopGif(
    val frames: List<ImageBitmap>,
    val delaysMs: List<Long>,
)

private data class GifFrameMeta(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val delayMs: Long,
    val disposal: String,
)

@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    contentDescription: String,
    fallbackImageUrl: String?,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
) {
    val shouldAnimate =
        animateIfPossible &&
            imageUrl.substringBefore('?').endsWith(".gif", ignoreCase = true)

    val gif by produceState<DesktopGif?>(
        initialValue = desktopGifCache[imageUrl],
        imageUrl,
        shouldAnimate,
    ) {
        value = when {
            !shouldAnimate -> null
            desktopGifCache[imageUrl] != null -> desktopGifCache[imageUrl]
            else -> withContext(Dispatchers.IO) {
                runCatching { loadDesktopGif(imageUrl) }.getOrNull()
            }
        }
    }

    val loadedGif = gif
    var frameIndex by remember(loadedGif) { mutableIntStateOf(0) }

    LaunchedEffect(loadedGif, shouldAnimate) {
        frameIndex = 0

        if (
            shouldAnimate &&
            loadedGif != null &&
            loadedGif.frames.isNotEmpty()
        ) {
            while (true) {
                delay(loadedGif.delaysMs.getOrElse(frameIndex) { 100L })
                frameIndex = (frameIndex + 1) % loadedGif.frames.size
            }
        }
    }

    val staticUrl = fallbackImageUrl
        ?.takeIf { it.isNotBlank() && it != imageUrl }
        ?: imageUrl

    val context = LocalPlatformContext.current
    val request = remember(context, staticUrl) {
        ImageRequest.Builder(context)
            .data(staticUrl)
            .memoryCacheKey("home-collection:$staticUrl")
            .diskCacheKey(staticUrl)
            .build()
    }

    Box(modifier = modifier) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
        )

        if (
            shouldAnimate &&
            loadedGif != null &&
            loadedGif.frames.isNotEmpty()
        ) {
            Image(
                bitmap = loadedGif.frames[frameIndex],
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

private const val DesktopGifCacheLimit = 12

private val desktopGifCache = Collections.synchronizedMap(
    object : LinkedHashMap<String, DesktopGif>(
        DesktopGifCacheLimit,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, DesktopGif>?,
        ): Boolean = size > DesktopGifCacheLimit
    },
)
private fun loadDesktopGif(url: String): DesktopGif {
    desktopGifCache[url]?.let { return it }

    val bytes = URL(url).openConnection().run {
        connectTimeout = 10_000
        readTimeout = 15_000
        getInputStream().use { it.readBytes() }
    }
    val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        ?: error("Unable to open GIF stream")
    input.use { stream ->
        val reader = ImageIO.getImageReadersByFormatName("gif").asSequence().firstOrNull()
            ?: error("No JVM GIF reader available")
        reader.input = stream
        try {
            val count = reader.getNumImages(true)
            require(count > 0) { "GIF contains no frames" }
            val logical = reader.streamMetadata?.let(::logicalScreenSize)
            val first = reader.read(0)
            val canvasWidth = max(logical?.first ?: 0, first.width)
            val canvasHeight = max(logical?.second ?: 0, first.height)
            var canvas = BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB)
            val frames = ArrayList<ImageBitmap>(count)
            val delays = ArrayList<Long>(count)
            var previousCanvas: BufferedImage? = null
            var previousMeta: GifFrameMeta? = null

            repeat(count) { index ->
                previousMeta?.let { meta ->
                    when (meta.disposal) {
                        "restoreToBackgroundColor" -> clearArea(canvas, meta)
                        "restoreToPrevious" -> previousCanvas?.let { canvas = deepCopy(it) }
                    }
                }

                val metadata = frameMeta(reader.getImageMetadata(index))
                if (metadata.disposal == "restoreToPrevious") previousCanvas = deepCopy(canvas)
                val raw = if (index == 0) first else reader.read(index)
                val graphics = canvas.createGraphics()
                try {
                    graphics.composite = AlphaComposite.SrcOver
                    graphics.drawImage(raw, metadata.left, metadata.top, null)
                } finally {
                    graphics.dispose()
                }
                frames += canvas.toImageBitmap()
                delays += metadata.delayMs.coerceAtLeast(20L)
                previousMeta = metadata
            }
            return DesktopGif(frames, delays).also { decodedGif ->
                desktopGifCache[url] = decodedGif
            }
        } finally {
            reader.dispose()
        }
    }
}

private fun frameMeta(metadata: IIOMetadata): GifFrameMeta {
    val root = metadata.getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
    val descriptor = root.findNode("ImageDescriptor")
    val control = root.findNode("GraphicControlExtension")
    return GifFrameMeta(
        left = descriptor?.attrInt("imageLeftPosition") ?: 0,
        top = descriptor?.attrInt("imageTopPosition") ?: 0,
        width = descriptor?.attrInt("imageWidth") ?: 0,
        height = descriptor?.attrInt("imageHeight") ?: 0,
        delayMs = ((control?.attrInt("delayTime") ?: 10) * 10L).coerceAtLeast(20L),
        disposal = control?.getAttribute("disposalMethod").orEmpty(),
    )
}

private fun logicalScreenSize(metadata: IIOMetadata): Pair<Int, Int>? {
    val root = metadata.getAsTree("javax_imageio_gif_stream_1.0") as IIOMetadataNode
    val descriptor = root.findNode("LogicalScreenDescriptor") ?: return null
    return descriptor.attrInt("logicalScreenWidth") to descriptor.attrInt("logicalScreenHeight")
}

private fun IIOMetadataNode.findNode(name: String): IIOMetadataNode? {
    if (nodeName == name) return this
    for (index in 0 until length) {
        val child = item(index) as? IIOMetadataNode ?: continue
        child.findNode(name)?.let { return it }
    }
    return null
}

private fun IIOMetadataNode.attrInt(name: String): Int =
    getAttribute(name).toIntOrNull() ?: 0

private fun clearArea(image: BufferedImage, meta: GifFrameMeta) {
    val graphics: Graphics2D = image.createGraphics()
    try {
        graphics.composite = AlphaComposite.Clear
        graphics.fillRect(meta.left, meta.top, meta.width, meta.height)
    } finally {
        graphics.dispose()
    }
}

private fun deepCopy(source: BufferedImage): BufferedImage {
    val copy = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = copy.createGraphics()
    try {
        graphics.composite = AlphaComposite.Src
        graphics.drawImage(source, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return copy
}

private fun BufferedImage.toImageBitmap(): ImageBitmap {
    val output = ByteArrayOutputStream()
    ImageIO.write(this, "png", output)
    val image = SkiaImage.makeFromEncoded(output.toByteArray())
    return try {
        org.jetbrains.skia.Bitmap.makeFromImage(image).asComposeImageBitmap()
    } finally {
        image.close()
    }
}


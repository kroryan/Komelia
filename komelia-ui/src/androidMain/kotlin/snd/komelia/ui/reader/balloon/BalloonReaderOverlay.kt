package snd.komelia.ui.reader.balloon

import android.graphics.Bitmap
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import snd.komelia.image.ReaderImage
import snd.komelia.ui.reader.image.paged.PagedReaderState

/**
 * Android actual implementation of balloon detection.
 * This effect handles detecting balloons and cropping them when selected.
 */
@Composable
actual fun BalloonDetectionEffect(
    pagedReaderState: PagedReaderState,
    currentPageImage: ReaderImage?
) {
    val context = LocalContext.current
    val balloonsState = pagedReaderState.balloonsState

    // Detector instance - created once and reused
    val detector = remember {
        try {
            AndroidBalloonDetectorProvider(context)
        } catch (e: Exception) {
            println("BalloonDetectionEffect: Failed to create detector: ${e.message}")
            null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            detector?.close()
        }
    }

    // Only process when balloons are enabled
    val balloonsEnabled by balloonsState.balloonsEnabled.collectAsState()

    // Store the current page bitmap for cropping
    var currentPageBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Detect balloons when page changes
    LaunchedEffect(currentPageImage, balloonsEnabled) {
        if (detector == null || !balloonsEnabled || currentPageImage == null) {
            balloonsState.clearBalloons()
            currentPageBitmap?.recycle()
            currentPageBitmap = null
            return@LaunchedEffect
        }

        try {
            val image = currentPageImage.getOriginalImage().getOrNull() ?: return@LaunchedEffect
            val bytes = image.getBytes()
            val width = image.width
            val height = image.height

            withContext(Dispatchers.Default) {
                // Convert RGBA bytes to Bitmap
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))

                // Store for cropping later
                currentPageBitmap?.recycle()
                currentPageBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)

                // Get reading direction from settings (default RTL for manga)
                val direction = when (pagedReaderState.readingDirection.value) {
                    snd.komelia.settings.model.PagedReadingDirection.LEFT_TO_RIGHT -> ReadingDirection.LTR
                    snd.komelia.settings.model.PagedReadingDirection.RIGHT_TO_LEFT -> ReadingDirection.RTL
                }

                // Detect balloons
                val balloons = detector.detectBalloons(
                    imageData = bitmap,
                    pageWidth = width,
                    pageHeight = height,
                    direction = direction
                )

                println("BalloonDetectionEffect: Detected ${balloons.size} balloons on page")

                balloonsState.setPageBalloons(balloons, width, height)

                bitmap.recycle()
            }

            image.close()
        } catch (e: Exception) {
            println("BalloonDetectionEffect: Error during detection: ${e.message}")
            e.printStackTrace()
            balloonsState.clearBalloons()
        }
    }

    // Crop balloon image when current balloon changes
    val currentBalloon by balloonsState.currentBalloon.collectAsState()

    LaunchedEffect(currentBalloon, currentPageBitmap) {
        val balloon = currentBalloon
        val pageBitmap = currentPageBitmap

        if (balloon == null || pageBitmap == null || pageBitmap.isRecycled) {
            balloonsState.setBalloonImage(null)
            return@LaunchedEffect
        }

        val croppedImage = withContext(Dispatchers.Default) {
            cropBalloonBitmap(pageBitmap, balloon)?.asImageBitmap()
        }

        balloonsState.setBalloonImage(croppedImage)
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            currentPageBitmap?.recycle()
            currentPageBitmap = null
        }
    }
}

/**
 * Android-specific balloon reader integration.
 * Provides balloon detection and navigation UI for standalone use.
 */
@Composable
fun BoxScope.BalloonReaderOverlay(
    balloonsState: BalloonsState,
    pageBitmap: Bitmap?,
    pageWidth: Int,
    pageHeight: Int,
    direction: ReadingDirection,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Detector instance
    val detector = remember {
        try {
            AndroidBalloonDetectorProvider(context)
        } catch (e: Exception) {
            println("BalloonReaderOverlay: Failed to create detector: ${e.message}")
            null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            detector?.close()
        }
    }

    // Detect balloons when page changes
    LaunchedEffect(pageBitmap) {
        if (detector == null || pageBitmap == null || pageBitmap.isRecycled) {
            balloonsState.clearBalloons()
            return@LaunchedEffect
        }

        val balloons = withContext(Dispatchers.Default) {
            detector.detectBalloons(
                imageData = pageBitmap,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                direction = direction
            )
        }

        balloonsState.setPageBalloons(balloons, pageWidth, pageHeight)
    }

    // Crop balloon image when current balloon changes
    val currentBalloon by balloonsState.currentBalloon.collectAsState()

    LaunchedEffect(currentBalloon, pageBitmap) {
        val balloon = currentBalloon
        if (balloon == null || pageBitmap == null || pageBitmap.isRecycled) {
            balloonsState.setBalloonImage(null)
            return@LaunchedEffect
        }

        val croppedImage = withContext(Dispatchers.Default) {
            cropBalloonBitmap(pageBitmap, balloon)?.asImageBitmap()
        }

        balloonsState.setBalloonImage(croppedImage)
    }

    // Show balloon overlay
    BalloonOverlay(
        balloonsState = balloonsState,
        balloonImage = null, // Uses state image
        modifier = modifier
    )
}

/**
 * Crop the balloon from the page bitmap with padding for better readability.
 * Adds a small margin around the balloon for visual clarity.
 */
private fun cropBalloonBitmap(pageBitmap: Bitmap, balloon: Balloon): Bitmap? {
    return try {
        // Add small padding (5% of balloon size) for visual clarity
        val paddingX = (balloon.width * 0.05f).toInt().coerceAtLeast(2)
        val paddingY = (balloon.height * 0.05f).toInt().coerceAtLeast(2)

        val left = (balloon.rect.left.toInt() - paddingX).coerceIn(0, pageBitmap.width - 1)
        val top = (balloon.rect.top.toInt() - paddingY).coerceIn(0, pageBitmap.height - 1)
        val right = (balloon.rect.right.toInt() + paddingX).coerceIn(left + 1, pageBitmap.width)
        val bottom = (balloon.rect.bottom.toInt() + paddingY).coerceIn(top + 1, pageBitmap.height)

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) {
            println("cropBalloonBitmap: Invalid dimensions width=$width height=$height")
            return null
        }

        Bitmap.createBitmap(pageBitmap, left, top, width, height)
    } catch (e: Exception) {
        println("cropBalloonBitmap: Error cropping balloon: ${e.message}")
        e.printStackTrace()
        null
    }
}

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import snd.komelia.image.ReaderImage
import snd.komelia.image.AndroidBitmap.toBitmap
import snd.komelia.ui.reader.image.PageMetadata
import snd.komelia.ui.reader.image.continuous.ContinuousReaderState

/**
 * Android actual implementation of balloon detection.
 * This effect handles detecting balloons and cropping them when selected.
 */
@Composable
actual fun BalloonDetectionEffect(
    balloonsState: BalloonsState,
    readingDirection: ReadingDirection,
    currentPageImage: ReaderImage?,
    preDetected: PageBalloons?
) {
    val context = LocalContext.current

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
    LaunchedEffect(currentPageImage, balloonsEnabled, preDetected) {
        if (detector == null || !balloonsEnabled) {
            balloonsState.setDetectorAvailable(detector != null)
            if (detector == null) {
                balloonsState.setDetectionError("Detector unavailable")
            }
            balloonsState.clearBalloons()
            currentPageBitmap?.recycle()
            currentPageBitmap = null
            return@LaunchedEffect
        }
        if (currentPageImage == null) {
            currentPageBitmap?.recycle()
            currentPageBitmap = null
            if (preDetected != null) {
                return@LaunchedEffect
            }
            balloonsState.clearBalloons()
            return@LaunchedEffect
        }

        if (preDetected == null) {
            balloonsState.setDetecting(true)
            balloonsState.setDetectorAvailable(true)
            balloonsState.setDetectionError(null)
            val modelInfo = try {
                detector.describeModel()
            } catch (e: Exception) {
                "model info unavailable"
            }
            balloonsState.setModelInfo(modelInfo)
        }

        val image = currentPageImage.getOriginalImage().getOrNull()
        if (image == null) {
            balloonsState.clearBalloons()
            return@LaunchedEffect
        }

        try {
            val width = image.width
            val height = image.height

            val (pageBitmap, balloons) = withContext(Dispatchers.Default) {
                val rawBitmap = image.toBitmap()
                val bitmap = rawBitmap.copy(Bitmap.Config.ARGB_8888, false).also {
                    if (rawBitmap != it) {
                        rawBitmap.recycle()
                    }
                }

                val detected = if (preDetected != null) {
                    preDetected.balloons
                } else {
                    detector.detectBalloons(
                        imageData = bitmap,
                        pageWidth = width,
                        pageHeight = height,
                        direction = readingDirection
                    )
                }

                bitmap to detected
            }

            if (preDetected == null) {
                println("BalloonDetectionEffect: Detected ${balloons.size} balloons on page")
            }

            // Store for cropping later
            currentPageBitmap?.recycle()
            currentPageBitmap = pageBitmap

            if (preDetected != null) {
                balloonsState.setPageBalloons(preDetected.balloons, preDetected.pageWidth, preDetected.pageHeight)
            } else {
                balloonsState.setPageBalloons(balloons, width, height)
            }
        } catch (e: Exception) {
            println("BalloonDetectionEffect: Error during detection: ${e.message}")
            e.printStackTrace()
            balloonsState.setDetectionError(e.message ?: "Detection error")
            balloonsState.clearBalloons()
        } finally {
            image.close()
            if (preDetected == null) {
                balloonsState.setDetecting(false)
            }
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

@Composable
actual fun BalloonIndexingEffect(
    pages: List<PageMetadata>,
    enabled: Boolean,
    readingDirection: ReadingDirection,
    continuousReaderState: ContinuousReaderState
) {
    val context = LocalContext.current

    val detector = remember {
        try {
            AndroidBalloonDetectorProvider(context)
        } catch (e: Exception) {
            println("BalloonIndexingEffect: Failed to create detector: ${e.message}")
            null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            detector?.close()
        }
    }

    LaunchedEffect(enabled, pages, readingDirection) {
        if (!enabled) {
            continuousReaderState.updateBalloonIndexProgress(continuousReaderState.balloonIndexTotal.value)
            return@LaunchedEffect
        }
        if (pages.isEmpty()) {
            continuousReaderState.clearBalloonIndex()
            return@LaunchedEffect
        }
        if (continuousReaderState.loadBalloonIndexFromStore(pages)) {
            return@LaunchedEffect
        }
        if (detector == null) {
            continuousReaderState.clearBalloonIndex()
            return@LaunchedEffect
        }

        continuousReaderState.resetBalloonIndex(pages.size)

        pages.forEachIndexed { index, page ->
            if (!isActive) return@LaunchedEffect
            val result = continuousReaderState.loadImageForBalloonIndex(page)
            val image = result.image
            if (image == null) {
                continuousReaderState.setBalloonIndexEntry(page, emptyList(), 0, 0)
                continuousReaderState.updateBalloonIndexProgress(index + 1)
                return@forEachIndexed
            }

            val original = image.getOriginalImage().getOrNull()
            if (original == null) {
                image.close()
                continuousReaderState.setBalloonIndexEntry(page, emptyList(), 0, 0)
                continuousReaderState.updateBalloonIndexProgress(index + 1)
                return@forEachIndexed
            }

            try {
                val width = original.width
                val height = original.height
                val balloons = withContext(Dispatchers.Default) {
                    val rawBitmap = original.toBitmap()
                    val bitmap = rawBitmap.copy(Bitmap.Config.ARGB_8888, false).also {
                        if (rawBitmap != it) {
                            rawBitmap.recycle()
                        }
                    }
                    detector.detectBalloons(
                        imageData = bitmap,
                        pageWidth = width,
                        pageHeight = height,
                        direction = readingDirection
                    ).also {
                        bitmap.recycle()
                    }
                }
                continuousReaderState.setBalloonIndexEntry(page, balloons, width, height)
            } catch (e: Exception) {
                println("BalloonIndexingEffect: Error indexing page ${page.pageNumber}: ${e.message}")
                continuousReaderState.setBalloonIndexEntry(page, emptyList(), 0, 0)
            } finally {
                image.close()
                continuousReaderState.updateBalloonIndexProgress(index + 1)
            }
        }
        continuousReaderState.persistBalloonIndex(pages)
    }
}

@Composable
actual fun BalloonIndexRefreshEffect(
    pages: List<PageMetadata>,
    enabled: Boolean,
    readingDirection: ReadingDirection,
    continuousReaderState: ContinuousReaderState
) {
    val context = LocalContext.current

    val detector = remember {
        try {
            AndroidBalloonDetectorProvider(context)
        } catch (e: Exception) {
            println("BalloonIndexRefreshEffect: Failed to create detector: ${e.message}")
            null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            detector?.close()
        }
    }

    LaunchedEffect(enabled, pages, readingDirection) {
        if (!enabled) return@LaunchedEffect
        if (detector == null || pages.isEmpty()) return@LaunchedEffect

        pages.forEach { page ->
            if (!isActive) return@LaunchedEffect
            if (!continuousReaderState.shouldRefreshBalloonIndex(page)) return@forEach

            continuousReaderState.markBalloonRefreshInProgress(page)
            val result = continuousReaderState.loadImageForBalloonIndex(page)
            val image = result.image
            if (image == null) {
                continuousReaderState.markBalloonRefreshDone(page)
                return@forEach
            }

            val original = image.getOriginalImage().getOrNull()
            if (original == null) {
                image.close()
                continuousReaderState.markBalloonRefreshDone(page)
                return@forEach
            }

            try {
                val width = original.width
                val height = original.height
                val balloons = withContext(Dispatchers.Default) {
                    val rawBitmap = original.toBitmap()
                    val bitmap = rawBitmap.copy(Bitmap.Config.ARGB_8888, false).also {
                        if (rawBitmap != it) {
                            rawBitmap.recycle()
                        }
                    }
                    detector.detectBalloons(
                        imageData = bitmap,
                        pageWidth = width,
                        pageHeight = height,
                        direction = readingDirection
                    ).also {
                        bitmap.recycle()
                    }
                }
                continuousReaderState.mergeBalloonIndexEntry(page, balloons, width, height, readingDirection)
            } catch (e: Exception) {
                println("BalloonIndexRefreshEffect: Error refreshing page ${page.pageNumber}: ${e.message}")
            } finally {
                image.close()
                continuousReaderState.markBalloonRefreshDone(page)
            }
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

package snd.komelia.ui.reader.balloon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Overlay that displays cropped balloon images with Seeneva-style proportional scaling.
 *
 * Key differences from simple overlay:
 * - Calculates scale based on balloon's original size and screen size
 * - Uses scaleXY factor (like Seeneva's 0.5dp resource) for consistent enlargement
 * - Limits max scale to prevent balloon from going outside visible area
 * - Centers balloon in screen with smooth animations
 */
@Composable
fun BoxScope.BalloonOverlay(
    balloonsState: BalloonsState,
    balloonImage: ImageBitmap? = null,
    modifier: Modifier = Modifier
) {
    val overlayVisible by balloonsState.overlayVisible.collectAsState()
    val currentBalloon by balloonsState.currentBalloon.collectAsState()
    val currentIndex by balloonsState.currentBalloonIndex.collectAsState()
    val balloonCount = balloonsState.getBalloonCount()
    val pageSize by balloonsState.pageSize.collectAsState()

    // Use passed image or state image
    val stateImage by balloonsState.balloonImage.collectAsState()
    val displayImage = balloonImage ?: stateImage

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()

        // Seeneva-style scale calculation
        // scaleXY is the additional scale factor (Seeneva uses 0.5dp from resources)
        val baseScaleXY = with(density) { 48.dp.toPx() } // Equivalent to 0.5dp * density, adjusted for Compose

        AnimatedVisibility(
            visible = overlayVisible && currentBalloon != null && displayImage != null,
            enter = fadeIn(
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + scaleIn(
                initialScale = 0.8f,
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ),
            exit = fadeOut(
                animationSpec = tween(150, easing = FastOutSlowInEasing)
            ) + scaleOut(
                targetScale = 2f, // "Blowing" animation like Seeneva
                animationSpec = tween(150, easing = FastOutSlowInEasing)
            ),
        ) {
            displayImage?.let { image ->
                currentBalloon?.let { balloon ->
                    // Calculate the target display size for the balloon
                    val (targetWidth, targetHeight) = remember(
                        balloon, screenWidthPx, screenHeightPx, image.width, image.height
                    ) {
                        calculateBalloonDisplaySize(
                            balloonWidth = balloon.width,
                            balloonHeight = balloon.height,
                            imageWidth = image.width.toFloat(),
                            imageHeight = image.height.toFloat(),
                            screenWidth = screenWidthPx,
                            screenHeight = screenHeightPx,
                            scaleXY = baseScaleXY
                        )
                    }

                    val targetWidthDp = with(density) { targetWidth.toDp() }
                    val targetHeightDp = with(density) { targetHeight.toDp() }

                    Box(
                        modifier = Modifier
                            .size(targetWidthDp, targetHeightDp)
                            .shadow(8.dp, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                    ) {
                        Image(
                            bitmap = image,
                            contentDescription = "Speech balloon ${currentIndex + 1} of $balloonCount",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp)
                        )
                    }
                }
            }
        }

        // Balloon counter indicator
        if (overlayVisible && balloonCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "${currentIndex + 1}/$balloonCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Calculate the display size for a balloon using Seeneva's approach.
 *
 * Seeneva's formula:
 * 1. Start with the original balloon bbox size
 * 2. Apply scaleXY (additional scale factor, typically 0.5dp converted to pixels)
 * 3. Limit the scale so balloon doesn't go outside visible area
 *
 * @param balloonWidth Original balloon width in pixels (from detection)
 * @param balloonHeight Original balloon height in pixels
 * @param imageWidth Width of the cropped balloon image
 * @param imageHeight Height of the cropped balloon image
 * @param screenWidth Available screen width
 * @param screenHeight Available screen height
 * @param scaleXY Additional scale factor (Seeneva uses ~48px on standard density)
 * @return Pair of (width, height) in pixels for display
 */
private fun calculateBalloonDisplaySize(
    balloonWidth: Float,
    balloonHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
    screenWidth: Float,
    screenHeight: Float,
    scaleXY: Float
): Pair<Float, Float> {
    // Use the actual cropped image dimensions as base
    val baseWidth = imageWidth
    val baseHeight = imageHeight

    // Calculate the scale factor similar to Seeneva
    // Seeneva: resultScaleXY = minScale + scaleXY
    // Here minScale is effectively 1.0 (showing at original size)
    // and we add scaleXY to enlarge
    val baseScale = 1.0f + (scaleXY / min(baseWidth, baseHeight))

    // Calculate max scale to prevent going outside screen bounds
    // Seeneva: maxScaleXY = min(screenWidth / balloonWidth, screenHeight / balloonHeight)
    val maxScaleWidth = (screenWidth * 0.90f) / baseWidth  // 90% of screen max
    val maxScaleHeight = (screenHeight * 0.85f) / baseHeight  // 85% of screen max
    val maxScale = min(maxScaleWidth, maxScaleHeight)

    // Apply the scale, but don't exceed max
    val finalScale = min(baseScale, maxScale).coerceAtLeast(1.0f)

    // Also ensure minimum readable size
    val minDisplayWidth = 100f
    val minDisplayHeight = 60f

    val displayWidth = (baseWidth * finalScale).coerceAtLeast(minDisplayWidth)
    val displayHeight = (baseHeight * finalScale).coerceAtLeast(minDisplayHeight)

    return Pair(displayWidth, displayHeight)
}

/**
 * Simple overlay without Seeneva-style scaling for testing/fallback
 */
@Composable
fun BoxScope.SimpleBalloonOverlay(
    visible: Boolean,
    balloonImage: ImageBitmap?,
    balloonIndex: Int,
    balloonCount: Int,
    modifier: Modifier = Modifier
) {
    if (visible && balloonImage != null) {
        Box(
            modifier = modifier
                .align(Alignment.Center)
                .shadow(8.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
        ) {
            Image(
                bitmap = balloonImage,
                contentDescription = "Speech balloon ${balloonIndex + 1} of $balloonCount",
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(4.dp)
            )
        }

        // Counter
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "${balloonIndex + 1}/$balloonCount",
                color = Color.White
            )
        }
    }
}

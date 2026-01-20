package snd.komelia.ui.reader.balloon

import androidx.compose.ui.geometry.Rect

/**
 * Platform-agnostic representation of a detected balloon
 */
data class Balloon(
    val index: Int,
    val rect: Rect,           // Absolute pixel coordinates
    val normalizedRect: Rect, // Normalized coordinates (0.0-1.0)
    val confidence: Float
) {
    val centerX: Float get() = rect.center.x
    val centerY: Float get() = rect.center.y
    val width: Float get() = rect.width
    val height: Float get() = rect.height
}

/**
 * Reading direction for balloon ordering
 */
enum class ReadingDirection {
    LTR, RTL
}

/**
 * Result of balloon detection for a page
 */
data class PageBalloons(
    val pageIndex: Int,
    val balloons: List<Balloon>,
    val pageWidth: Int,
    val pageHeight: Int
)

/**
 * Interface for platform-specific balloon detection
 */
interface BalloonDetectorProvider {
    /**
     * Detect balloons in the given image data
     * @param imageData Platform-specific image data (Bitmap on Android)
     * @param pageWidth Width of the page in pixels
     * @param pageHeight Height of the page in pixels
     * @param direction Reading direction for ordering
     * @return List of balloons sorted in reading order
     */
    suspend fun detectBalloons(
        imageData: Any,
        pageWidth: Int,
        pageHeight: Int,
        direction: ReadingDirection
    ): List<Balloon>
    
    /**
     * Check if balloon detection is available on this platform
     */
    fun isAvailable(): Boolean
    
    /**
     * Close and release resources
     */
    fun close()
}

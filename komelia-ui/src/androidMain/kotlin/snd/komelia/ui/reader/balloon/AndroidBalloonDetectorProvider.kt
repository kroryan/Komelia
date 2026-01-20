package snd.komelia.ui.reader.balloon

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of balloon detection using TensorFlow Lite
 */
class AndroidBalloonDetectorProvider(
    private val context: Context
) : BalloonDetectorProvider {

    private companion object {
        private const val TAG = "KomeliaBalloon"
    }
    
    private var detector: BalloonDetector? = null
    private var initializationError: Exception? = null
    
    private fun getOrCreateDetector(): BalloonDetector? {
        if (detector != null) return detector
        
        return try {
            BalloonDetector(context).also { detector = it }
        } catch (e: Exception) {
            initializationError = e
            null
        }
    }
    
    override suspend fun detectBalloons(
        imageData: Any,
        pageWidth: Int,
        pageHeight: Int,
        direction: ReadingDirection
    ): List<Balloon> = withContext(Dispatchers.Default) {
        val bitmap = imageData as? Bitmap ?: return@withContext emptyList()
        val det = getOrCreateDetector() ?: return@withContext emptyList()
        
        try {
            // Detect objects
            val detectedObjects = det.detect(bitmap)
            if (detectedObjects.isNotEmpty()) {
                val classCounts = detectedObjects.groupingBy { it.classId }.eachCount()
                Log.d(TAG, "Detected objects: total=${detectedObjects.size} classCounts=$classCounts")
            } else {
                Log.d(TAG, "Detected objects: none")
            }
            
            // Filter only speech balloons and sort in reading order
            val pageDirection = when (direction) {
                ReadingDirection.LTR -> PageObjectHelper.Direction.LTR
                ReadingDirection.RTL -> PageObjectHelper.Direction.RTL
            }
            
            val sortedBalloons = PageObjectHelper.generateReadOrderedObjects(
                objects = detectedObjects,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                direction = pageDirection
            )
            
            // Convert to common Balloon type
            sortedBalloons.mapIndexed { index, pageBalloon ->
                Balloon(
                    index = index,
                    rect = Rect(
                        left = pageBalloon.bbox.left,
                        top = pageBalloon.bbox.top,
                        right = pageBalloon.bbox.right,
                        bottom = pageBalloon.bbox.bottom
                    ),
                    normalizedRect = Rect(
                        left = pageBalloon.normalizedBbox.xMin,
                        top = pageBalloon.normalizedBbox.yMin,
                        right = pageBalloon.normalizedBbox.xMax,
                        bottom = pageBalloon.normalizedBbox.yMax
                    ),
                    confidence = pageBalloon.confidence
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    override fun isAvailable(): Boolean {
        return try {
            getOrCreateDetector() != null
        } catch (e: Exception) {
            false
        }
    }

    fun describeModel(): String {
        return try {
            getOrCreateDetector()?.describeModel() ?: "detector unavailable"
        } catch (e: Exception) {
            "model info unavailable"
        }
    }
    
    override fun close() {
        detector?.close()
        detector = null
    }
}

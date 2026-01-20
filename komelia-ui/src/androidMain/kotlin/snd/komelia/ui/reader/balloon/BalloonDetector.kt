package snd.komelia.ui.reader.balloon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream
import kotlin.math.exp

/**
 * Balloon detector using TensorFlow Lite with YOLOv4-tiny model
 * Based on Seeneva's yolo_seeneva.tflite model
 */
class BalloonDetector(context: Context) : Closeable {
    
    companion object {
        private const val MODEL_FILE = "yolo_seeneva.tflite"
        private const val INPUT_SIZE = 416 // YOLOv4-tiny input size
        private const val NUM_CLASSES = 2 // speech_balloon=0, panel=1
        // Increased confidence threshold to reduce false positives (Seeneva uses similar approach)
        private const val CONFIDENCE_THRESHOLD = 0.40f
        private const val NMS_THRESHOLD = 0.45f

        // Minimum and maximum balloon size relative to page (to filter out noise)
        private const val MIN_BALLOON_SIZE = 0.01f  // Min 1% of page dimension
        private const val MAX_BALLOON_SIZE = 0.70f  // Max 70% of page dimension
        
        // YOLOv4-tiny anchors for 2 output layers
        private val ANCHORS = arrayOf(
            floatArrayOf(81f, 82f, 135f, 169f, 344f, 319f), // Layer 1 (13x13)
            floatArrayOf(23f, 27f, 37f, 58f, 81f, 82f)      // Layer 2 (26x26)
        )
        private val ANCHOR_MASKS = arrayOf(
            intArrayOf(3, 4, 5), // Layer 1
            intArrayOf(0, 1, 2)  // Layer 2
        )
    }
    
    private val interpreter: Interpreter
    private val inputBuffer: ByteBuffer
    
    init {
        val modelBuffer = loadModelFile(context.assets, MODEL_FILE)
        
        // Create interpreter without GPU (simpler and more compatible)
        interpreter = Interpreter(modelBuffer)
        
        // Allocate input buffer (NHWC format, float32)
        inputBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        inputBuffer.order(ByteOrder.nativeOrder())
    }
    
    private fun loadModelFile(assetManager: android.content.res.AssetManager, modelPath: String): MappedByteBuffer {
        val fileDescriptor = assetManager.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    
    /**
     * Detect objects (balloons and panels) in the given bitmap
     * @param bitmap The image to analyze
     * @return List of detected objects with normalized coordinates (0.0-1.0)
     */
    fun detect(bitmap: Bitmap): List<DetectedObject> {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        
        // Fill input buffer
        inputBuffer.rewind()
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaledBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        
        for (pixel in pixels) {
            // Normalize to 0-1 range
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)          // B
        }
        
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        
        // Get output shapes from interpreter
        val outputCount = interpreter.outputTensorCount
        val outputs = mutableMapOf<Int, Any>()
        
        for (i in 0 until outputCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            // YOLOv4-tiny outputs: [1, gridH, gridW, anchors * (5 + numClasses)]
            outputs[i] = Array(shape[0]) { Array(shape[1]) { Array(shape[2]) { FloatArray(shape[3]) } } }
        }
        
        // Run inference
        inputBuffer.rewind()
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
        
        // Parse outputs
        val allDetections = mutableListOf<DetectedObject>()
        
        for ((layerIndex, output) in outputs) {
            @Suppress("UNCHECKED_CAST")
            val outputArray = output as Array<Array<Array<FloatArray>>>
            val gridH = outputArray[0].size
            val gridW = outputArray[0][0].size
            val anchors = ANCHORS.getOrElse(layerIndex) { ANCHORS[0] }
            val anchorMask = ANCHOR_MASKS.getOrElse(layerIndex) { ANCHOR_MASKS[0] }
            
            // NOTE: anchors are pixel values relative to INPUT_SIZE.  They are
            // used directly in the width/height calculation below.
            parseYoloOutput(outputArray[0], gridW, gridH, anchors, anchorMask, allDetections)
        }
        
        // Apply NMS
        val nmsDetections = nonMaxSuppression(allDetections)

        // Filter out balloons that are too small or too large (noise reduction)
        val finalDetections = nmsDetections.filter { det ->
            val width = det.xMax - det.xMin
            val height = det.yMax - det.yMin

            // Check size constraints
            val validWidth = width in MIN_BALLOON_SIZE..MAX_BALLOON_SIZE
            val validHeight = height in MIN_BALLOON_SIZE..MAX_BALLOON_SIZE

            // Also filter out extremely thin or flat detections (aspect ratio check)
            val aspectRatio = if (height > 0) width / height else 0f
            val validAspect = aspectRatio in 0.15f..6.0f  // Reasonable balloon aspect ratio

            validWidth && validHeight && validAspect
        }

        // Debug: print the normalized boxes (reduced verbosity)
        println("BalloonDetector: ${allDetections.size} raw -> ${nmsDetections.size} NMS -> ${finalDetections.size} filtered")

        return finalDetections
    }
    
    private fun parseYoloOutput(
        output: Array<Array<FloatArray>>,
        gridW: Int,
        gridH: Int,
        anchors: FloatArray,
        anchorMask: IntArray,
        detections: MutableList<DetectedObject>
    ) {
        val numAnchors = anchorMask.size
        
        for (cy in 0 until gridH) {
            for (cx in 0 until gridW) {
                val cellData = output[cy][cx]
                val numValues = 5 + NUM_CLASSES // x, y, w, h, conf, class0, class1
                
                for (a in 0 until numAnchors) {
                    val offset = a * numValues
                    
                    val objectness = sigmoid(cellData[offset + 4])
                    if (objectness < CONFIDENCE_THRESHOLD) continue
                    
                    // Get class scores
                    var maxClassScore = 0f
                    var maxClassId = 0
                    for (c in 0 until NUM_CLASSES) {
                        val score = sigmoid(cellData[offset + 5 + c])
                        if (score > maxClassScore) {
                            maxClassScore = score
                            maxClassId = c
                        }
                    }
                    
                    val confidence = objectness * maxClassScore
                    if (confidence < CONFIDENCE_THRESHOLD) continue
                    
                    // Get bounding box (normalized to 0-1)
                    val anchorIdx = anchorMask[a]
                    val anchorW = anchors[anchorIdx * 2] / INPUT_SIZE
                    val anchorH = anchors[anchorIdx * 2 + 1] / INPUT_SIZE
                    
                    val bx = (sigmoid(cellData[offset]) + cx) / gridW
                    val by = (sigmoid(cellData[offset + 1]) + cy) / gridH
                    val bw = exp(cellData[offset + 2].toDouble()).toFloat() * anchorW
                    val bh = exp(cellData[offset + 3].toDouble()).toFloat() * anchorH
                    
                    // Convert from center format to corner format (still normalized)
                    val xMin = (bx - bw / 2).coerceIn(0f, 1f)
                    val yMin = (by - bh / 2).coerceIn(0f, 1f)
                    val xMax = (bx + bw / 2).coerceIn(0f, 1f)
                    val yMax = (by + bh / 2).coerceIn(0f, 1f)
                    
                    detections.add(
                        DetectedObject(
                            classId = maxClassId,
                            confidence = confidence,
                            xMin = xMin,
                            yMin = yMin,
                            xMax = xMax,
                            yMax = yMax
                        )
                    )
                }
            }
        }
    }
    
    private fun nonMaxSuppression(detections: List<DetectedObject>): List<DetectedObject> {
        if (detections.isEmpty()) return emptyList()
        
        // Group by class
        val grouped = detections.groupBy { it.classId }
        val result = mutableListOf<DetectedObject>()
        
        for ((_, classDetections) in grouped) {
            val sorted = classDetections.sortedByDescending { it.confidence }.toMutableList()
            
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                result.add(best)
                
                sorted.removeAll { other ->
                    computeIoU(best, other) > NMS_THRESHOLD
                }
            }
        }
        
        return result
    }
    
    private fun computeIoU(a: DetectedObject, b: DetectedObject): Float {
        val intersectXMin = maxOf(a.xMin, b.xMin)
        val intersectYMin = maxOf(a.yMin, b.yMin)
        val intersectXMax = minOf(a.xMax, b.xMax)
        val intersectYMax = minOf(a.yMax, b.yMax)
        
        val intersectWidth = maxOf(0f, intersectXMax - intersectXMin)
        val intersectHeight = maxOf(0f, intersectYMax - intersectYMin)
        val intersectArea = intersectWidth * intersectHeight
        
        val areaA = (a.xMax - a.xMin) * (a.yMax - a.yMin)
        val areaB = (b.xMax - b.xMin) * (b.yMax - b.yMin)
        val unionArea = areaA + areaB - intersectArea
        
        return if (unionArea > 0) intersectArea / unionArea else 0f
    }
    
    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x.toDouble()).toFloat())
    
    override fun close() {
        interpreter.close()
    }
}

/**
 * Represents a detected object with normalized coordinates (0.0-1.0)
 * Similar to Seeneva's ComicPageObject
 */
data class DetectedObject(
    val classId: Int, // 0 = SPEECH_BALLOON, 1 = PANEL
    val confidence: Float,
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float
) {
    val objectClass: ObjectClass
        get() = ObjectClass.fromId(classId)
    
    fun toRectF(pageWidth: Float, pageHeight: Float): RectF {
        return RectF(
            xMin * pageWidth,
            yMin * pageHeight,
            xMax * pageWidth,
            yMax * pageHeight
        )
    }
}

enum class ObjectClass(val id: Int) {
    SPEECH_BALLOON(0),
    PANEL(1);
    
    companion object {
        fun fromId(id: Int): ObjectClass = entries.find { it.id == id } ?: SPEECH_BALLOON
    }
}

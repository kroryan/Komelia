package snd.komelia.ui.reader.balloon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
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
        private const val NUM_CLASSES = 2 // speech_balloon=0, panel=1
        // Increased confidence threshold to reduce false positives (Seeneva uses similar approach)
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val NMS_THRESHOLD = 0.45f
        private const val TAG = "KomeliaBalloon"

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
    private val inputDataType: DataType
    private val inputBuffer: ByteBuffer
    private val inputBatch: Int
    private val inputHeight: Int
    private val inputWidth: Int
    private val inputChannels: Int
    private val usePostProcessOutputs: Boolean
    
    init {
        val modelBuffer = loadModelFile(context.assets, MODEL_FILE)
        
        // Create interpreter without GPU (simpler and more compatible)
        interpreter = Interpreter(modelBuffer)
        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()
        inputDataType = inputTensor.dataType()
        inputBatch = inputShape.getOrNull(0) ?: 1
        inputHeight = inputShape.getOrNull(1) ?: 416
        inputWidth = inputShape.getOrNull(2) ?: 416
        inputChannels = inputShape.getOrNull(3) ?: 3

        // Allocate input buffer (NHWC format)
        val inputSizeBytes = when (inputDataType) {
            DataType.UINT8 -> inputBatch * inputHeight * inputWidth * inputChannels
            else -> 4 * inputBatch * inputHeight * inputWidth * inputChannels
        }
        inputBuffer = ByteBuffer.allocateDirect(inputSizeBytes)
        inputBuffer.order(ByteOrder.nativeOrder())

        val output0Shape = interpreter.getOutputTensor(0).shape()
        val output1Shape = interpreter.getOutputTensor(1).shape()
        val output2Shape = interpreter.getOutputTensor(2).shape()
        usePostProcessOutputs = interpreter.outputTensorCount == 3 &&
            output0Shape.size == 3 &&
            output0Shape.lastOrNull() == 5 &&
            output1Shape.size == 2 &&
            output2Shape.size == 1

        Log.d(
            TAG,
            "Model IO: in=${inputShape.joinToString("x")}:${inputDataType} " +
                "out0=${output0Shape.joinToString("x")}:${interpreter.getOutputTensor(0).dataType()} " +
                "out1=${output1Shape.joinToString("x")}:${interpreter.getOutputTensor(1).dataType()} " +
                "out2=${output2Shape.joinToString("x")}:${interpreter.getOutputTensor(2).dataType()} " +
                "postprocess=$usePostProcessOutputs"
        )
    }

    fun describeModel(): String {
        val inputShape = interpreter.getInputTensor(0).shape().joinToString("x")
        val inputType = interpreter.getInputTensor(0).dataType().toString()
        val outputInfo = buildString {
            for (i in 0 until interpreter.outputTensorCount) {
                val tensor = interpreter.getOutputTensor(i)
                val shape = tensor.shape().joinToString("x")
                if (isNotEmpty()) append(" | ")
                append("out$i=$shape:${tensor.dataType()}")
            }
        }
        return "in=$inputShape:$inputType | $outputInfo"
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
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val pixels = IntArray(inputWidth * inputHeight)
        scaledBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        fun allocateOutputs(): MutableMap<Int, Any> {
            val outputs = mutableMapOf<Int, Any>()
            val outputCount = interpreter.outputTensorCount
            for (i in 0 until outputCount) {
                val shape = interpreter.getOutputTensor(i).shape()
                outputs[i] = when (shape.size) {
                    5 -> Array(shape[0]) { Array(shape[1]) { Array(shape[2]) { Array(shape[3]) { FloatArray(shape[4]) } } } }
                    4 -> Array(shape[0]) { Array(shape[1]) { Array(shape[2]) { FloatArray(shape[3]) } } }
                    3 -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
                    2 -> Array(shape[0]) { FloatArray(shape[1]) }
                    else -> FloatArray(shape.reduce { acc, v -> acc * v })
                }
            }
            return outputs
        }

        fun allocatePostProcessOutputs(): MutableMap<Int, Any> {
            val outputs = mutableMapOf<Int, Any>()
            val output0Shape = interpreter.getOutputTensor(0).shape()
            val batch = output0Shape.getOrNull(0) ?: 1
            val maxDetections = output0Shape.getOrNull(1) ?: 50
            outputs[0] = Array(batch) { Array(maxDetections) { FloatArray(5) } }

            val output1Type = interpreter.getOutputTensor(1).dataType()
            outputs[1] = when (output1Type) {
                DataType.INT32 -> Array(batch) { IntArray(maxDetections) }
                else -> Array(batch) { FloatArray(maxDetections) }
            }

            val output2Type = interpreter.getOutputTensor(2).dataType()
            outputs[2] = when (output2Type) {
                DataType.INT32 -> IntArray(batch)
                else -> FloatArray(batch)
            }
            return outputs
        }

        fun fillInputBuffer(useBgr: Boolean) {
            inputBuffer.rewind()
            repeat(inputBatch.coerceAtLeast(1)) {
                for (pixel in pixels) {
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val c0 = if (useBgr) b else r
                    val c2 = if (useBgr) r else b
                    when (inputDataType) {
                        DataType.UINT8 -> {
                            inputBuffer.put(c0.toByte())
                            inputBuffer.put(g.toByte())
                            inputBuffer.put(c2.toByte())
                        }
                        else -> {
                            inputBuffer.putFloat(c0 / 255.0f)
                            inputBuffer.putFloat(g / 255.0f)
                            inputBuffer.putFloat(c2 / 255.0f)
                        }
                    }
                }
            }
        }

        fun runInference(outputs: MutableMap<Int, Any>): MutableMap<Int, Any> {
            inputBuffer.rewind()
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
            return outputs
        }

        fun parseOutputs(outputs: Map<Int, Any>): MutableList<DetectedObject> {
            val allDetections = mutableListOf<DetectedObject>()
            val outputCount = interpreter.outputTensorCount
            for (layerIndex in 0 until outputCount) {
                val shape = interpreter.getOutputTensor(layerIndex).shape()
                val anchors = ANCHORS.getOrElse(layerIndex) { ANCHORS[0] }
                val anchorMask = ANCHOR_MASKS.getOrElse(layerIndex) { ANCHOR_MASKS[0] }
                when (shape.size) {
                    5 -> {
                        @Suppress("UNCHECKED_CAST")
                        val outputArray = outputs[layerIndex] as Array<Array<Array<Array<FloatArray>>>>
                        val gridH = shape[1]
                        val gridW = shape[2]
                        val numAnchors = shape[3]
                        val numValues = shape[4]
                        parseYoloOutput5D(
                            output = outputArray[0],
                            gridW = gridW,
                            gridH = gridH,
                            numAnchors = numAnchors,
                            numValues = numValues,
                            anchors = anchors,
                            anchorMask = anchorMask,
                            detections = allDetections
                        )
                    }
                    4 -> {
                        @Suppress("UNCHECKED_CAST")
                        val outputArray = outputs[layerIndex] as Array<Array<Array<FloatArray>>>
                        val gridH = shape[1]
                        val gridW = shape[2]
                        val values = shape[3]
                        if (values >= (5 + NUM_CLASSES) && values % (5 + NUM_CLASSES) == 0) {
                            parseYoloOutput(
                                output = outputArray[0],
                                gridW = gridW,
                                gridH = gridH,
                                anchors = anchors,
                                anchorMask = anchorMask,
                                detections = allDetections
                            )
                        }
                    }
                    3 -> {
                        @Suppress("UNCHECKED_CAST")
                        val outputArray = outputs[layerIndex] as Array<Array<FloatArray>>
                        val count = shape[1]
                        val values = shape[2]
                        parseYoloOutput3D(
                            output = outputArray[0],
                            count = count,
                            values = values,
                            detections = allDetections
                        )
                    }
                    else -> {
                        // Unsupported output layout
                    }
                }
            }
            return allDetections
        }

        fun parsePostProcess(outputs: Map<Int, Any>): List<DetectedObject> {
            @Suppress("UNCHECKED_CAST")
            val boxes = outputs[0] as? Array<Array<FloatArray>> ?: return emptyList()
            val maxDetections = boxes.firstOrNull()?.size ?: return emptyList()
            val batchIndex = 0

            val classIds: IntArray? = when (val raw = outputs[1]) {
                is Array<*> -> (raw as? Array<IntArray>)?.getOrNull(batchIndex)
                else -> null
            }
            val classIdsFloat: FloatArray? = when (val raw = outputs[1]) {
                is Array<*> -> (raw as? Array<FloatArray>)?.getOrNull(batchIndex)
                else -> null
            }

            val countValue = when (val raw = outputs[2]) {
                is IntArray -> raw.getOrNull(batchIndex)?.toInt()
                is FloatArray -> raw.getOrNull(batchIndex)?.toInt()
                is Array<*> -> (raw as? Array<IntArray>)?.getOrNull(0)?.getOrNull(batchIndex)
                else -> null
            }
            val total = countValue?.coerceIn(0, maxDetections) ?: maxDetections

            val detections = mutableListOf<DetectedObject>()
            for (i in 0 until total) {
                val box = boxes[batchIndex][i]
                if (box.size < 5) continue
                val score = box[4]
                if (score < CONFIDENCE_THRESHOLD) continue
                val classId = classIds?.getOrNull(i)
                    ?: classIdsFloat?.getOrNull(i)?.toInt()
                    ?: 0

                var yMin = box[0]
                var xMin = box[1]
                var yMax = box[2]
                var xMax = box[3]

                if (xMax > 1f || yMax > 1f) {
                    xMin /= inputWidth.toFloat()
                    xMax /= inputWidth.toFloat()
                    yMin /= inputHeight.toFloat()
                    yMax /= inputHeight.toFloat()
                }

                detections.add(
                    DetectedObject(
                        classId = classId,
                        confidence = score,
                        xMin = xMin.coerceIn(0f, 1f),
                        yMin = yMin.coerceIn(0f, 1f),
                        xMax = xMax.coerceIn(0f, 1f),
                        yMax = yMax.coerceIn(0f, 1f)
                    )
                )
            }
            return detections
        }

        // Try RGB first, then BGR if nothing detected
        fillInputBuffer(useBgr = false)
        var outputs = runInference(if (usePostProcessOutputs) allocatePostProcessOutputs() else allocateOutputs())
        var allDetections = if (usePostProcessOutputs) {
            parsePostProcess(outputs).toMutableList()
        } else {
            parseOutputs(outputs).also { if (it.isEmpty()) it.addAll(tryParseSsd(outputs)) }
        }
        if (allDetections.isEmpty()) {
            fillInputBuffer(useBgr = true)
            outputs = runInference(if (usePostProcessOutputs) allocatePostProcessOutputs() else allocateOutputs())
            allDetections = if (usePostProcessOutputs) {
                parsePostProcess(outputs).toMutableList()
            } else {
                parseOutputs(outputs).also { if (it.isEmpty()) it.addAll(tryParseSsd(outputs)) }
            }
        }
        
        // Apply NMS
        val nmsDetections = nonMaxSuppression(allDetections)

        // Filter out balloons that are too small or too large (noise reduction)
        var finalDetections = nmsDetections.filter { det ->
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
        if (finalDetections.isEmpty() && nmsDetections.isNotEmpty()) {
            finalDetections = nmsDetections
        }

        Log.d(
            TAG,
            "detect: raw=${allDetections.size} nms=${nmsDetections.size} filtered=${finalDetections.size}"
        )

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
                    val anchorW = anchors[anchorIdx * 2] / inputWidth
                    val anchorH = anchors[anchorIdx * 2 + 1] / inputHeight
                    
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

    private fun parseYoloOutput5D(
        output: Array<Array<Array<FloatArray>>>,
        gridW: Int,
        gridH: Int,
        numAnchors: Int,
        numValues: Int,
        anchors: FloatArray,
        anchorMask: IntArray,
        detections: MutableList<DetectedObject>
    ) {
        val mask = if (anchorMask.size == numAnchors) anchorMask else IntArray(numAnchors) { it }
        for (cy in 0 until gridH) {
            for (cx in 0 until gridW) {
                for (a in 0 until numAnchors) {
                    val cellData = output[cy][cx][a]
                    if (cellData.size < numValues) continue

                    val objectness = sigmoid(cellData[4])
                    if (objectness < CONFIDENCE_THRESHOLD) continue

                    var maxClassScore = 0f
                    var maxClassId = 0
                    for (c in 0 until NUM_CLASSES) {
                        val score = sigmoid(cellData[5 + c])
                        if (score > maxClassScore) {
                            maxClassScore = score
                            maxClassId = c
                        }
                    }

                    val confidence = objectness * maxClassScore
                    if (confidence < CONFIDENCE_THRESHOLD) continue

                    val anchorIdx = mask[a]
                    val anchorW = anchors[anchorIdx * 2] / inputWidth
                    val anchorH = anchors[anchorIdx * 2 + 1] / inputHeight

                    val bx = (sigmoid(cellData[0]) + cx) / gridW
                    val by = (sigmoid(cellData[1]) + cy) / gridH
                    val bw = exp(cellData[2].toDouble()).toFloat() * anchorW
                    val bh = exp(cellData[3].toDouble()).toFloat() * anchorH

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

    private fun parseYoloOutput3D(
        output: Array<FloatArray>,
        count: Int,
        values: Int,
        detections: MutableList<DetectedObject>
    ) {
        for (i in 0 until count) {
            val row = output[i]
            if (row.size < values) continue
            if (values >= (5 + NUM_CLASSES)) {
                val obj = row[4]
                if (obj < CONFIDENCE_THRESHOLD) continue

                var maxClassScore = 0f
                var maxClassId = 0
                for (c in 0 until NUM_CLASSES) {
                    val score = row[5 + c]
                    if (score > maxClassScore) {
                        maxClassScore = score
                        maxClassId = c
                    }
                }

                val confidence = obj * maxClassScore
                if (confidence < CONFIDENCE_THRESHOLD) continue

                decodeBoxFromRow(row[0], row[1], row[2], row[3], maxClassId, confidence, detections)
            } else if (values >= 6) {
                val confidence = row[4]
                if (confidence < CONFIDENCE_THRESHOLD) continue
                val classId = row[5].toInt().coerceIn(0, NUM_CLASSES - 1)
                decodeBoxFromRow(row[0], row[1], row[2], row[3], classId, confidence, detections)
            }
        }
    }

    private fun decodeBoxFromRow(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        classId: Int,
        confidence: Float,
        detections: MutableList<DetectedObject>
    ) {
        // Assume center-based boxes; normalize if needed
        val normX = if (x > 1f) x / inputWidth else x
        val normY = if (y > 1f) y / inputHeight else y
        val normW = if (w > 1f) w / inputWidth else w
        val normH = if (h > 1f) h / inputHeight else h

        val xMin = (normX - normW / 2).coerceIn(0f, 1f)
        val yMin = (normY - normH / 2).coerceIn(0f, 1f)
        val xMax = (normX + normW / 2).coerceIn(0f, 1f)
        val yMax = (normY + normH / 2).coerceIn(0f, 1f)

        detections.add(
            DetectedObject(
                classId = classId,
                confidence = confidence,
                xMin = xMin,
                yMin = yMin,
                xMax = xMax,
                yMax = yMax
            )
        )
    }

    private fun tryParseSsd(outputs: Map<Int, Any>): List<DetectedObject> {
        if (interpreter.outputTensorCount < 4) return emptyList()
        val boxesTensor = interpreter.getOutputTensor(0)
        val classesTensor = interpreter.getOutputTensor(1)
        val scoresTensor = interpreter.getOutputTensor(2)
        val countTensor = interpreter.getOutputTensor(3)

        if (boxesTensor.shape().size != 3 || boxesTensor.shape().last() != 4) return emptyList()

        @Suppress("UNCHECKED_CAST")
        val boxes = outputs[0] as? Array<Array<FloatArray>> ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val classes = outputs[1] as? Array<FloatArray> ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val scores = outputs[2] as? Array<FloatArray> ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val count = outputs[3] as? FloatArray ?: return emptyList()

        val maxCount = boxes[0].size
        val validCount = count.firstOrNull()?.toInt()?.coerceIn(0, maxCount) ?: maxCount

        val detections = mutableListOf<DetectedObject>()
        for (i in 0 until validCount) {
            val score = scores[0].getOrNull(i) ?: continue
            if (score < CONFIDENCE_THRESHOLD) continue
            val classId = classes[0].getOrNull(i)?.toInt() ?: 0
            val box = boxes[0].getOrNull(i) ?: continue
            if (box.size < 4) continue
            // SSD boxes are typically [ymin, xmin, ymax, xmax] normalized 0..1
            val yMin = box[0].coerceIn(0f, 1f)
            val xMin = box[1].coerceIn(0f, 1f)
            val yMax = box[2].coerceIn(0f, 1f)
            val xMax = box[3].coerceIn(0f, 1f)
            detections.add(
                DetectedObject(
                    classId = classId,
                    confidence = score,
                    xMin = xMin,
                    yMin = yMin,
                    xMax = xMax,
                    yMax = yMax
                )
            )
        }

        return detections
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

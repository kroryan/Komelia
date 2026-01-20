package snd.komelia.ui.reader.balloon

import android.graphics.RectF
import kotlin.math.abs

/**
 * Helper to order page objects (balloons) in reading order
 * Ported from Seeneva's PageObjectHelper.kt
 */
object PageObjectHelper {
    
    // Reference page dimensions for proportional calculations (same as Seeneva)
    private const val REF_PAGE_WIDTH = 1988
    private const val REF_PAGE_HEIGHT = 3056
    
    // Sorting thresholds (scaled proportionally to actual page size)
    private const val PANEL_MIN_DIFF = 160.0f
    private const val GROUP_MIN_DIFF = 80.0f
    private const val OBJECT_NEIGHBOUR_MIN_DIFF = 20.0f
    private const val OBJECT_MIN_DIFF = 15.0f
    private const val OBJECT_BENEATH = 0.15f // 15%
    
    enum class Direction {
        LTR, RTL
    }
    
    /**
     * Sorts detected objects in reading order based on direction
     * Only returns speech balloons, panels are used for grouping
     */
    fun generateReadOrderedObjects(
        objects: List<DetectedObject>,
        pageWidth: Int,
        pageHeight: Int,
        direction: Direction
    ): List<PageBalloon> {
        if (objects.isEmpty()) return emptyList()
        
        // Separate speech balloons and panels
        val balloons = objects.filter { it.classId == ObjectClass.SPEECH_BALLOON.id }
        val panels = objects.filter { it.classId == ObjectClass.PANEL.id }
        
        if (balloons.isEmpty()) return emptyList()
        
        // Convert to PageBalloon with absolute pixel coordinates
        val pageBalloons = balloons.map { obj ->
            PageBalloon(
                bbox = obj.toRectF(pageWidth.toFloat(), pageHeight.toFloat()),
                confidence = obj.confidence,
                normalizedBbox = NormalizedRect(obj.xMin, obj.yMin, obj.xMax, obj.yMax)
            )
        }
        
        // Calculate scale factors for thresholds
        val scaleX = pageWidth.toFloat() / REF_PAGE_WIDTH
        val scaleY = pageHeight.toFloat() / REF_PAGE_HEIGHT
        val avgScale = (scaleX + scaleY) / 2
        
        val scaledObjectMinDiff = OBJECT_MIN_DIFF * avgScale
        val scaledGroupMinDiff = GROUP_MIN_DIFF * avgScale
        val scaledNeighbourMinDiff = OBJECT_NEIGHBOUR_MIN_DIFF * avgScale
        
        // Sort using direction-aware comparator
        val sorted = sortBalloons(pageBalloons, direction, scaledObjectMinDiff, scaledGroupMinDiff, scaledNeighbourMinDiff)
        // Debug: number of groups and balloons
        println("PageObjectHelper: found ${balloons.size} balloons, ${sorted.size} groups after sorting")
        return sorted
    }
    
    private fun sortBalloons(
        balloons: List<PageBalloon>,
        direction: Direction,
        objectMinDiff: Float,
        groupMinDiff: Float,
        neighbourMinDiff: Float
    ): List<PageBalloon> {
        if (balloons.size <= 1) return balloons
        
        // Group nearby balloons
        val groups = groupNearbyBalloons(balloons, neighbourMinDiff)
        
        // Sort groups by reading order
        val sortedGroups = groups.sortedWith(
            compareBy<List<PageBalloon>> { group ->
                group.minOfOrNull { it.bbox.top } ?: 0f
            }.thenBy { group ->
                when (direction) {
                    Direction.LTR -> group.minOfOrNull { it.bbox.left } ?: 0f
                    Direction.RTL -> -(group.maxOfOrNull { it.bbox.right } ?: 0f)
                }
            }
        )
        
        // Sort within each group
        return sortedGroups.flatMap { group ->
            sortWithinGroup(group, direction, objectMinDiff)
        }
    }
    
    private fun groupNearbyBalloons(
        balloons: List<PageBalloon>,
        neighbourMinDiff: Float
    ): List<List<PageBalloon>> {
        val groups = mutableListOf<MutableList<PageBalloon>>()
        val assigned = mutableSetOf<PageBalloon>()
        
        for (balloon in balloons) {
            if (balloon in assigned) continue
            
            val group = mutableListOf(balloon)
            assigned.add(balloon)
            
            // Find all neighbors
            var i = 0
            while (i < group.size) {
                val current = group[i]
                for (other in balloons) {
                    if (other in assigned) continue
                    if (areNeighbors(current, other, neighbourMinDiff)) {
                        group.add(other)
                        assigned.add(other)
                    }
                }
                i++
            }
            
            groups.add(group)
        }
        
        return groups
    }
    
    private fun areNeighbors(a: PageBalloon, b: PageBalloon, threshold: Float): Boolean {
        // Check if boxes are close enough to be neighbors
        val horizontalGap = maxOf(0f, maxOf(a.bbox.left, b.bbox.left) - minOf(a.bbox.right, b.bbox.right))
        val verticalGap = maxOf(0f, maxOf(a.bbox.top, b.bbox.top) - minOf(a.bbox.bottom, b.bbox.bottom))
        
        return horizontalGap <= threshold && verticalGap <= threshold
    }
    
    private fun sortWithinGroup(
        group: List<PageBalloon>,
        direction: Direction,
        objectMinDiff: Float
    ): List<PageBalloon> {
        if (group.size <= 1) return group
        
        return group.sortedWith(
            compareBy<PageBalloon> { balloon ->
                // Primary: top position with tolerance
                (balloon.bbox.top / objectMinDiff).toInt() * objectMinDiff
            }.thenBy { balloon ->
                // Secondary: horizontal position based on direction
                when (direction) {
                    Direction.LTR -> balloon.bbox.left
                    Direction.RTL -> -balloon.bbox.right
                }
            }
        )
    }
}

/**
 * Represents a detected balloon with both pixel and normalized coordinates
 */
data class PageBalloon(
    val bbox: RectF,
    val confidence: Float,
    val normalizedBbox: NormalizedRect
) {
    val centerX: Float get() = bbox.centerX()
    val centerY: Float get() = bbox.centerY()
    val width: Float get() = bbox.width()
    val height: Float get() = bbox.height()
}

/**
 * Normalized bounding box (0.0-1.0 coordinates)
 */
data class NormalizedRect(
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float
) {
    fun toRectF(pageWidth: Float, pageHeight: Float): RectF {
        return RectF(
            xMin * pageWidth,
            yMin * pageHeight,
            xMax * pageWidth,
            yMax * pageHeight
        )
    }
}

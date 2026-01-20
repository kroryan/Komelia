package snd.komelia.ui.reader.balloon

import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State management for balloon navigation
 * Follows Seeneva's pattern: readObjectPosition starts at -1, shows popup overlays
 */
@Stable
class BalloonsState(
    private val scope: CoroutineScope,
    private val onNextPage: () -> Unit,
    private val onPreviousPage: () -> Unit
) {
    // Current balloon index (-1 means no balloon selected yet, like Seeneva's readObjectPosition)
    private val _currentBalloonIndex = MutableStateFlow(-1)
    val currentBalloonIndex: StateFlow<Int> = _currentBalloonIndex.asStateFlow()
    
    // List of balloons for current page
    private val _balloons = MutableStateFlow<List<Balloon>>(emptyList())
    val balloons: StateFlow<List<Balloon>> = _balloons.asStateFlow()
    
    // Current balloon to display (null when hidden)
    private val _currentBalloon = MutableStateFlow<Balloon?>(null)
    val currentBalloon: StateFlow<Balloon?> = _currentBalloon.asStateFlow()
    
    // Whether balloon mode is enabled
    private val _balloonsEnabled = MutableStateFlow(false)
    val balloonsEnabled: StateFlow<Boolean> = _balloonsEnabled.asStateFlow()
    
    // Whether the balloon overlay is visible
    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    // Whether detection is running for the current page
    private val _isDetecting = MutableStateFlow(false)
    val isDetecting: StateFlow<Boolean> = _isDetecting.asStateFlow()

    // Detector availability/error for UI feedback
    private val _detectorAvailable = MutableStateFlow(true)
    val detectorAvailable: StateFlow<Boolean> = _detectorAvailable.asStateFlow()

    private val _lastDetectionError = MutableStateFlow<String?>(null)
    val lastDetectionError: StateFlow<String?> = _lastDetectionError.asStateFlow()

    private val _modelInfo = MutableStateFlow<String?>(null)
    val modelInfo: StateFlow<String?> = _modelInfo.asStateFlow()

    // Animation state for popup
    private val _isAnimating = MutableStateFlow(false)
    val isAnimating: StateFlow<Boolean> = _isAnimating.asStateFlow()
    
    // Page size for coordinate calculations
    private val _pageSize = MutableStateFlow(IntSize.Zero)
    val pageSize: StateFlow<IntSize> = _pageSize.asStateFlow()

    private val _pageDisplaySize = MutableStateFlow(IntSize.Zero)
    val pageDisplaySize: StateFlow<IntSize> = _pageDisplaySize.asStateFlow()

    private val _pageDisplayOffset = MutableStateFlow(IntOffset.Zero)
    val pageDisplayOffset: StateFlow<IntOffset> = _pageDisplayOffset.asStateFlow()
    
    // Cropped balloon image for display
    private val _balloonImage = MutableStateFlow<androidx.compose.ui.graphics.ImageBitmap?>(null)
    val balloonImage: StateFlow<androidx.compose.ui.graphics.ImageBitmap?> = _balloonImage.asStateFlow()
    
    /**
     * Set the cropped balloon image for display
     */
    fun setBalloonImage(image: androidx.compose.ui.graphics.ImageBitmap?) {
        _balloonImage.value = image
    }
    
    /**
     * Set the balloons for the current page
     * Called when page changes or detection completes
     */
    fun setPageBalloons(balloons: List<Balloon>, pageWidth: Int, pageHeight: Int) {
        _balloons.value = balloons
        _pageSize.value = IntSize(pageWidth, pageHeight)
        // Reset to -1 like Seeneva's resetReadPageObject()
        _currentBalloonIndex.value = -1
        _currentBalloon.value = null
        _overlayVisible.value = false
        _isDetecting.value = false
    }
    
    /**
     * Clear balloons (e.g., when leaving balloon mode)
     */
    fun clearBalloons() {
        _balloons.value = emptyList()
        _currentBalloonIndex.value = -1
        _currentBalloon.value = null
        _overlayVisible.value = false
        _balloonImage.value = null
        _isDetecting.value = false
        _lastDetectionError.value = null
        _modelInfo.value = null
        _pageDisplaySize.value = IntSize.Zero
        _pageDisplayOffset.value = IntOffset.Zero
    }

    fun setPageDisplayLayout(displaySize: IntSize, displayOffset: IntOffset) {
        _pageDisplaySize.value = displaySize
        _pageDisplayOffset.value = displayOffset
    }

    /**
     * Track detection state for the current page
     */
    fun setDetecting(detecting: Boolean) {
        _isDetecting.value = detecting
    }

    fun setDetectorAvailable(available: Boolean) {
        _detectorAvailable.value = available
    }

    fun setDetectionError(message: String?) {
        _lastDetectionError.value = message
    }

    fun setModelInfo(info: String?) {
        _modelInfo.value = info
    }
    
    /**
     * Toggle balloon mode on/off
     */
    fun setBalloonsEnabled(enabled: Boolean) {
        _balloonsEnabled.value = enabled
        if (!enabled) {
            clearBalloons()
        }
    }
    
    /**
     * Navigate to next balloon or next page
     * Following Seeneva's FORWARD logic
     */
    fun nextBalloon() {
        val balloons = _balloons.value
        if (balloons.isEmpty()) {
            onNextPage()
            return
        }
        
        val currentIndex = _currentBalloonIndex.value
        val nextIndex = currentIndex + 1
        
        scope.launch {
            // If overlay is visible, hide it first with animation (like Seeneva's "blowing" animation)
            if (_overlayVisible.value) {
                hideOverlayWithAnimation()
            }
            
            if (nextIndex < balloons.size) {
                // Show next balloon
                _currentBalloonIndex.value = nextIndex
                showBalloon(balloons[nextIndex])
            } else {
                // No more balloons, go to next page
                _currentBalloonIndex.value = -1
                _currentBalloon.value = null
                onNextPage()
            }
        }
    }
    
    /**
     * Navigate to previous balloon or previous page
     * Following Seeneva's BACKWARD logic
     */
    fun previousBalloon() {
        val balloons = _balloons.value
        if (balloons.isEmpty()) {
            onPreviousPage()
            return
        }
        
        val currentIndex = _currentBalloonIndex.value
        
        scope.launch {
            // If overlay is visible, hide it first
            if (_overlayVisible.value) {
                hideOverlayWithAnimation()
            }
            
            when {
                currentIndex > 0 -> {
                    // Show previous balloon
                    val prevIndex = currentIndex - 1
                    _currentBalloonIndex.value = prevIndex
                    showBalloon(balloons[prevIndex])
                }
                currentIndex == 0 -> {
                    // At first balloon, go to previous page
                    _currentBalloonIndex.value = -1
                    _currentBalloon.value = null
                    onPreviousPage()
                }
                else -> {
                    // currentIndex is -1, go to previous page
                    onPreviousPage()
                }
            }
        }
    }
    
    /**
     * Hide the current balloon overlay
     */
    fun hideBalloon() {
        scope.launch {
            hideOverlayWithAnimation()
            _currentBalloon.value = null
        }
    }
    
    private fun showBalloon(balloon: Balloon) {
        _currentBalloon.value = balloon
        _overlayVisible.value = true
        _isAnimating.value = true
        scope.launch {
            delay(200) // Show animation duration (like Seeneva)
            _isAnimating.value = false
        }
    }
    
    private suspend fun hideOverlayWithAnimation() {
        _isAnimating.value = true
        delay(150) // Hide animation duration (Seeneva's "blowing" animation)
        _overlayVisible.value = false
        _isAnimating.value = false
    }
    
    /**
     * Handle tap on screen
     * Returns true if tap was handled by balloon system
     */
    fun handleTap(tapPosition: Offset, screenSize: IntSize, direction: ReadingDirection): Boolean {
        if (!_balloonsEnabled.value || _balloons.value.isEmpty()) {
            return false
        }
        
        // Calculate screen zones (like Seeneva)
        val screenWidth = screenSize.width.toFloat()
        val hideZoneStart = screenWidth * 0.35f
        val hideZoneEnd = screenWidth * 0.65f
        
        val tapX = tapPosition.x
        
        when {
            // Left zone
            tapX < hideZoneStart -> {
                when (direction) {
                    ReadingDirection.LTR -> previousBalloon()
                    ReadingDirection.RTL -> nextBalloon()
                }
            }
            // Right zone
            tapX > hideZoneEnd -> {
                when (direction) {
                    ReadingDirection.LTR -> nextBalloon()
                    ReadingDirection.RTL -> previousBalloon()
                }
            }
            // Center zone - hide if visible
            else -> {
                if (_overlayVisible.value) {
                    hideBalloon()
                } else {
                    return false // Let parent handle tap
                }
            }
        }
        
        return true
    }
    
    /**
     * Get the current balloon for display
     */
    fun getCurrentBalloon(): Balloon? = _currentBalloon.value
    
    /**
     * Check if there are balloons to navigate
     */
    fun hasBalloons(): Boolean = _balloons.value.isNotEmpty()
    
    /**
     * Get balloon count
     */
    fun getBalloonCount(): Int = _balloons.value.size
}

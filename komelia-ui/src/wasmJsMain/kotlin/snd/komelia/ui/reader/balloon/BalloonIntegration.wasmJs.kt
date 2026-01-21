package snd.komelia.ui.reader.balloon

import androidx.compose.runtime.Composable
import snd.komelia.image.ReaderImage

/**
 * WASM/Web implementation - balloon detection not yet supported
 */
@Composable
actual fun BalloonDetectionEffect(
    balloonsState: BalloonsState,
    readingDirection: ReadingDirection,
    currentPageImage: ReaderImage?,
    preDetected: PageBalloons?
) {
    // No-op on web for now
    // Could be implemented with TensorFlow.js in the future
}

@Composable
actual fun BalloonIndexingEffect(
    pages: List<snd.komelia.ui.reader.image.PageMetadata>,
    enabled: Boolean,
    readingDirection: ReadingDirection,
    continuousReaderState: snd.komelia.ui.reader.image.continuous.ContinuousReaderState
) {
    // No-op on web for now
}

@Composable
actual fun BalloonIndexRefreshEffect(
    pages: List<snd.komelia.ui.reader.image.PageMetadata>,
    enabled: Boolean,
    readingDirection: ReadingDirection,
    continuousReaderState: snd.komelia.ui.reader.image.continuous.ContinuousReaderState
) {
    // No-op on web for now
}

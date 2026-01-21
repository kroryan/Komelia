package snd.komelia.ui.reader.balloon

import androidx.compose.runtime.Composable
import snd.komelia.image.ReaderImage

/**
 * JVM/Desktop implementation - balloon detection not yet supported
 */
@Composable
actual fun BalloonDetectionEffect(
    balloonsState: BalloonsState,
    readingDirection: ReadingDirection,
    currentPageImage: ReaderImage?,
    preDetected: PageBalloons?
) {
    // No-op on desktop for now
    // TensorFlow Lite is Android-specific
    // Could be implemented with TensorFlow Java or ONNX Runtime in the future
}

@Composable
actual fun BalloonIndexingEffect(
    pages: List<snd.komelia.ui.reader.image.PageMetadata>,
    enabled: Boolean,
    readingDirection: ReadingDirection,
    continuousReaderState: snd.komelia.ui.reader.image.continuous.ContinuousReaderState
) {
    // No-op on desktop for now
}

@Composable
actual fun BalloonIndexRefreshEffect(
    pages: List<snd.komelia.ui.reader.image.PageMetadata>,
    enabled: Boolean,
    readingDirection: ReadingDirection,
    continuousReaderState: snd.komelia.ui.reader.image.continuous.ContinuousReaderState
) {
    // No-op on desktop for now
}

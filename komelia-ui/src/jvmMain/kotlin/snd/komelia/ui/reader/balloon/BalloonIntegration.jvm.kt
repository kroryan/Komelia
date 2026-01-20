package snd.komelia.ui.reader.balloon

import androidx.compose.runtime.Composable
import snd.komelia.image.ReaderImage
import snd.komelia.ui.reader.image.paged.PagedReaderState

/**
 * JVM/Desktop implementation - balloon detection not yet supported
 */
@Composable
actual fun BalloonDetectionEffect(
    pagedReaderState: PagedReaderState,
    currentPageImage: ReaderImage?
) {
    // No-op on desktop for now
    // TensorFlow Lite is Android-specific
    // Could be implemented with TensorFlow Java or ONNX Runtime in the future
}

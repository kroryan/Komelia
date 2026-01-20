package snd.komelia.ui.reader.balloon

import androidx.compose.runtime.Composable
import snd.komelia.image.ReaderImage
import snd.komelia.ui.reader.image.paged.PagedReaderState

/**
 * WASM/Web implementation - balloon detection not yet supported
 */
@Composable
actual fun BalloonDetectionEffect(
    pagedReaderState: PagedReaderState,
    currentPageImage: ReaderImage?
) {
    // No-op on web for now
    // Could be implemented with TensorFlow.js in the future
}

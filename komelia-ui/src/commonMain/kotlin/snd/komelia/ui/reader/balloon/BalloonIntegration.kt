package snd.komelia.ui.reader.balloon

import androidx.compose.runtime.Composable
import snd.komelia.image.ReaderImage
import snd.komelia.ui.reader.image.paged.PagedReaderState

/**
 * Balloon detection integration interface
 * Implemented per platform (Android uses TFLite, others may use different backends or no-op)
 */
@Composable
expect fun BalloonDetectionEffect(
    pagedReaderState: PagedReaderState,
    currentPageImage: ReaderImage?
)

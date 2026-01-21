package snd.komelia.ui.reader.balloon

import androidx.compose.runtime.Composable
import snd.komelia.image.ReaderImage
import snd.komelia.ui.reader.image.PageMetadata
import snd.komelia.ui.reader.image.continuous.ContinuousReaderState
/**
 * Balloon detection integration interface
 * Implemented per platform (Android uses TFLite, others may use different backends or no-op)
 */
@Composable
expect fun BalloonDetectionEffect(
    balloonsState: BalloonsState,
    readingDirection: ReadingDirection,
    currentPageImage: ReaderImage?,
    preDetected: PageBalloons?
)

@Composable
expect fun BalloonIndexingEffect(
    pages: List<PageMetadata>,
    enabled: Boolean,
    readingDirection: ReadingDirection,
    continuousReaderState: ContinuousReaderState
)

@Composable
expect fun BalloonIndexRefreshEffect(
    pages: List<PageMetadata>,
    enabled: Boolean,
    readingDirection: ReadingDirection,
    continuousReaderState: ContinuousReaderState
)

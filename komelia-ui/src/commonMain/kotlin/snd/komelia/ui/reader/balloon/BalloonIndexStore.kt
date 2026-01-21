package snd.komelia.ui.reader.balloon

import androidx.compose.ui.geometry.Rect
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import snd.komga.client.book.KomgaBookId

@Serializable
data class StoredBalloonRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

@Serializable
data class StoredBalloon(
    val index: Int,
    val rect: StoredBalloonRect,
    val normalizedRect: StoredBalloonRect,
    val confidence: Float,
)

@Serializable
data class StoredPageBalloons(
    val pageNumber: Int,
    val pageWidth: Int,
    val pageHeight: Int,
    val balloons: List<StoredBalloon>,
)

@Serializable
data class StoredBalloonIndex(
    val pages: List<StoredPageBalloons>,
)

interface BalloonIndexStore {
    suspend fun load(bookId: KomgaBookId): StoredBalloonIndex?
    suspend fun save(bookId: KomgaBookId, index: StoredBalloonIndex)
    suspend fun clear(bookId: KomgaBookId)
}

expect fun createBalloonIndexStore(cacheDir: Path?): BalloonIndexStore?

fun PageBalloons.toStored(): StoredPageBalloons {
    return StoredPageBalloons(
        pageNumber = pageIndex,
        pageWidth = pageWidth,
        pageHeight = pageHeight,
        balloons = balloons.map { balloon ->
            StoredBalloon(
                index = balloon.index,
                rect = StoredBalloonRect(
                    left = balloon.rect.left,
                    top = balloon.rect.top,
                    right = balloon.rect.right,
                    bottom = balloon.rect.bottom,
                ),
                normalizedRect = StoredBalloonRect(
                    left = balloon.normalizedRect.left,
                    top = balloon.normalizedRect.top,
                    right = balloon.normalizedRect.right,
                    bottom = balloon.normalizedRect.bottom,
                ),
                confidence = balloon.confidence,
            )
        }
    )
}

fun StoredPageBalloons.toPageBalloons(): PageBalloons {
    return PageBalloons(
        pageIndex = pageNumber,
        balloons = balloons.map { balloon ->
            val normalized = Rect(
                left = balloon.normalizedRect.left,
                top = balloon.normalizedRect.top,
                right = balloon.normalizedRect.right,
                bottom = balloon.normalizedRect.bottom,
            )
            val rect = Rect(
                left = normalized.left * pageWidth,
                top = normalized.top * pageHeight,
                right = normalized.right * pageWidth,
                bottom = normalized.bottom * pageHeight,
            )
            Balloon(
                index = balloon.index,
                rect = rect,
                normalizedRect = normalized,
                confidence = balloon.confidence,
            )
        },
        pageWidth = pageWidth,
        pageHeight = pageHeight,
    )
}

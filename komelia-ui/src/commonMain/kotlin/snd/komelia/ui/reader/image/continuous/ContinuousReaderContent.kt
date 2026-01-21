package snd.komelia.ui.reader.image.continuous

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType.Companion.KeyDown
import androidx.compose.ui.input.key.KeyEventType.Companion.KeyUp
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection.Ltr
import androidx.compose.ui.unit.LayoutDirection.Rtl
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch
import snd.komelia.image.ReaderImageResult
import snd.komelia.settings.model.ContinuousReadingDirection.LEFT_TO_RIGHT
import snd.komelia.settings.model.ContinuousReadingDirection.RIGHT_TO_LEFT
import snd.komelia.settings.model.ContinuousReadingDirection.TOP_TO_BOTTOM
import snd.komelia.ui.reader.balloon.BalloonDetectionEffect
import snd.komelia.ui.reader.balloon.BalloonIndexingEffect
import snd.komelia.ui.reader.balloon.BalloonIndexRefreshEffect
import snd.komelia.ui.reader.balloon.BalloonOverlay
import snd.komelia.ui.reader.balloon.ReadingDirection
import snd.komelia.ui.reader.image.PageMetadata
import snd.komelia.ui.reader.image.ScreenScaleState
import snd.komelia.ui.reader.image.common.ContinuousReaderHelpDialog
import snd.komelia.ui.reader.image.common.ReaderControlsOverlay
import snd.komelia.ui.reader.image.common.ReaderImageContent
import snd.komelia.ui.reader.image.common.ScalableContainer
import snd.komelia.ui.reader.image.continuous.ContinuousReaderState.BookPagesInterval

@Composable
fun BoxScope.ContinuousReaderContent(
    showHelpDialog: Boolean,
    onShowHelpDialogChange: (Boolean) -> Unit,
    showSettingsMenu: Boolean,
    onShowSettingsMenuChange: (Boolean) -> Unit,
    screenScaleState: ScreenScaleState,
    continuousReaderState: ContinuousReaderState,
    volumeKeysNavigation: Boolean
) {
    val coroutineScope = rememberCoroutineScope()
    val readingDirection = continuousReaderState.readingDirection.collectAsState().value
    val balloonsState = continuousReaderState.balloonsState
    val balloonsEnabled = balloonsState.balloonsEnabled.collectAsState().value
    val balloonsDetecting = balloonsState.isDetecting.collectAsState().value
    val hasBalloons = balloonsState.balloons.collectAsState().value.isNotEmpty()
    val currentBalloon = balloonsState.currentBalloon.collectAsState().value
    val currentBalloonIndex = balloonsState.currentBalloonIndex.collectAsState().value
    val overlayVisible = balloonsState.overlayVisible.collectAsState().value
    val balloonDisplaySize = balloonsState.pageDisplaySize.collectAsState().value
    val balloonDisplayOffset = balloonsState.pageDisplayOffset.collectAsState().value
    val indexInProgress = continuousReaderState.balloonIndexing.collectAsState().value
    val indexProgress = continuousReaderState.balloonIndexProgress.collectAsState().value
    val indexTotal = continuousReaderState.balloonIndexTotal.collectAsState().value
    val balloonDirection = when (readingDirection) {
        RIGHT_TO_LEFT -> ReadingDirection.RTL
        LEFT_TO_RIGHT, TOP_TO_BOTTOM -> ReadingDirection.LTR
    }
    var lastCenteredBalloon by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(overlayVisible) {
        if (!overlayVisible) {
            lastCenteredBalloon = null
        }
    }

    val layoutDirection = remember(readingDirection) {
        when (readingDirection) {
            TOP_TO_BOTTOM -> Ltr
            LEFT_TO_RIGHT -> Ltr
            RIGHT_TO_LEFT -> Rtl
        }
    }

    if (showHelpDialog) {
        ContinuousReaderHelpDialog(
            readingDirection = readingDirection,
            onDismissRequest = { onShowHelpDialogChange(false) }
        )
    }

    val areaSize = screenScaleState.areaSize.collectAsState().value
    val density = LocalDensity.current
    val pageSpacingDp = continuousReaderState.pageSpacing.collectAsState().value
    val pageSpacingPx = with(density) { pageSpacingDp.dp.toPx() }
    val keysState = remember(readingDirection, volumeKeysNavigation) {
        KeyMapState(
            readingDirection = readingDirection,
            volumeKeysNavigation = volumeKeysNavigation,
            scrollBy = continuousReaderState::scrollBy,
            scrollForward = { coroutineScope.launch { continuousReaderState.scrollScreenForward() } },
            scrollBackward = { coroutineScope.launch { continuousReaderState.scrollScreenBackward() } },
            scrollToFirstPage = { coroutineScope.launch { continuousReaderState.scrollToBookPage(0) } },
            scrollToLastPage = { coroutineScope.launch { continuousReaderState.scrollToLastPage() } },
            changeReadingDirection = continuousReaderState::onReadingDirectionChange
        )
    }
    var currentPage by remember { mutableStateOf<PageMetadata?>(null) }
    var currentPageImage by remember { mutableStateOf<snd.komelia.image.ReaderImage?>(null) }
    var pageDisplaySize by remember { mutableStateOf(IntSize.Zero) }
    val indexedPages = continuousReaderState.balloonIndex.collectAsState().value
    val indexedEntry = currentPage?.let { page ->
        indexedPages[snd.komelia.ui.reader.image.continuous.BalloonPageKey(page.bookId, page.pageNumber)]
    }

    LaunchedEffect(balloonsEnabled, readingDirection, areaSize) {
        if (!balloonsEnabled) {
            currentPage = null
            return@LaunchedEffect
        }
        snapshotFlow { continuousReaderState.lazyListState.layoutInfo }.collect { layoutInfo ->
            val visibleItems = layoutInfo.visibleItemsInfo.filter { it.key is PageMetadata }
            if (visibleItems.isEmpty()) return@collect
            val viewportCenter = when (readingDirection) {
                TOP_TO_BOTTOM -> areaSize.height / 2
                LEFT_TO_RIGHT, RIGHT_TO_LEFT -> areaSize.width / 2
            }
            val closest = visibleItems.minByOrNull { item ->
                val itemCenter = item.offset + (item.size / 2)
                abs(itemCenter - viewportCenter)
            }
            currentPage = closest?.key as? PageMetadata
        }
    }

    LaunchedEffect(currentPage) {
        pageDisplaySize = IntSize.Zero
        val page = currentPage ?: run {
            currentPageImage = null
            return@LaunchedEffect
        }
        continuousReaderState.getPageDisplaySize(page).collect { pageDisplaySize = it }
    }

    LaunchedEffect(currentPage, balloonsEnabled) {
        val page = currentPage
        if (!balloonsEnabled || page == null) {
            currentPageImage = null
            balloonsState.clearBalloons()
            return@LaunchedEffect
        }
        currentPageImage = continuousReaderState.waitForImage(page)
    }

    val pagesForIndex = continuousReaderState.currentBookPages.collectAsState(initial = emptyList()).value
    BalloonIndexingEffect(
        pages = pagesForIndex,
        enabled = balloonsEnabled,
        readingDirection = balloonDirection,
        continuousReaderState = continuousReaderState
    )
    val refreshPages = remember(currentPage, pagesForIndex) {
        val page = currentPage ?: return@remember emptyList()
        val nextIndex = page.pageNumber
        val nextPage = pagesForIndex.getOrNull(nextIndex)
        listOfNotNull(page, nextPage)
    }
    BalloonIndexRefreshEffect(
        pages = refreshPages,
        enabled = balloonsEnabled,
        readingDirection = balloonDirection,
        continuousReaderState = continuousReaderState
    )

    BalloonDetectionEffect(
        balloonsState = balloonsState,
        readingDirection = balloonDirection,
        currentPageImage = currentPageImage,
        preDetected = indexedEntry
    )

    LaunchedEffect(balloonsEnabled, indexInProgress, indexedEntry) {
        if (!balloonsEnabled) return@LaunchedEffect
        balloonsState.setDetecting(indexInProgress && indexedEntry == null)
    }

    LaunchedEffect(currentPage, indexedEntry, overlayVisible, currentBalloonIndex) {
        val entry = indexedEntry ?: return@LaunchedEffect
        if (overlayVisible || currentBalloonIndex >= 0) return@LaunchedEffect
        balloonsState.setPageBalloons(entry.balloons, entry.pageWidth, entry.pageHeight)
    }

    LaunchedEffect(currentPage, pageDisplaySize, readingDirection, areaSize, pageSpacingPx) {
        val page = currentPage
        if (page == null) {
            balloonsState.setPageDisplayLayout(IntSize.Zero, IntOffset.Zero)
            return@LaunchedEffect
        }
        snapshotFlow { continuousReaderState.lazyListState.layoutInfo }.collect { layoutInfo ->
            val item = layoutInfo.visibleItemsInfo.firstOrNull { it.key == page } ?: return@collect
            val fallbackSize = if (readingDirection == TOP_TO_BOTTOM) {
                IntSize(
                    width = item.size,
                    height = (item.size - pageSpacingPx).toInt().coerceAtLeast(1)
                )
            } else {
                IntSize(
                    width = (item.size - pageSpacingPx).toInt().coerceAtLeast(1),
                    height = item.size
                )
            }
            val displaySize = if (pageDisplaySize != IntSize.Zero) pageDisplaySize else fallbackSize
            val horizontalPadding = if (readingDirection == TOP_TO_BOTTOM) {
                continuousReaderState.sidePaddingPx.value
            } else {
                0
            }
            val verticalPadding = if (readingDirection != TOP_TO_BOTTOM) {
                continuousReaderState.sidePaddingPx.value
            } else {
                0
            }
            val availableWidth = (areaSize.width - (horizontalPadding * 2)).coerceAtLeast(0)
            val availableHeight = (areaSize.height - (verticalPadding * 2)).coerceAtLeast(0)
            val offsetX = if (readingDirection == TOP_TO_BOTTOM) {
                horizontalPadding + ((availableWidth - displaySize.width) / 2).coerceAtLeast(0)
            } else {
                item.offset
            }
            val offsetY = if (readingDirection == TOP_TO_BOTTOM) {
                item.offset
            } else {
                verticalPadding + ((availableHeight - displaySize.height) / 2).coerceAtLeast(0)
            }
            balloonsState.setPageDisplayLayout(displaySize, IntOffset(offsetX, offsetY))
        }
    }

    LaunchedEffect(
        balloonsEnabled,
        currentBalloon,
        overlayVisible,
        balloonDisplaySize,
        balloonDisplayOffset,
        areaSize,
        readingDirection
    ) {
        val balloon = currentBalloon ?: return@LaunchedEffect
        if (!balloonsEnabled || !overlayVisible) return@LaunchedEffect
        if (balloonDisplaySize == IntSize.Zero) return@LaunchedEffect
        val pageNumber = currentPage?.pageNumber ?: return@LaunchedEffect
        val balloonKey = pageNumber to balloon.index
        if (lastCenteredBalloon == balloonKey) return@LaunchedEffect

        val centerOnPage = when (readingDirection) {
            TOP_TO_BOTTOM -> balloon.normalizedRect.center.y * balloonDisplaySize.height
            LEFT_TO_RIGHT, RIGHT_TO_LEFT -> balloon.normalizedRect.center.x * balloonDisplaySize.width
        }
        val targetOnScreen = when (readingDirection) {
            TOP_TO_BOTTOM -> balloonDisplayOffset.y + centerOnPage
            LEFT_TO_RIGHT, RIGHT_TO_LEFT -> balloonDisplayOffset.x + centerOnPage
        }
        val screenCenter = when (readingDirection) {
            TOP_TO_BOTTOM -> areaSize.height / 2f
            LEFT_TO_RIGHT, RIGHT_TO_LEFT -> areaSize.width / 2f
        }
        val delta = targetOnScreen - screenCenter
        if (kotlin.math.abs(delta) < 8f) return@LaunchedEffect

        continuousReaderState.scrollByFromUi(delta)
        lastCenteredBalloon = balloonKey
    }

    ReaderControlsOverlay(
        readingDirection = layoutDirection,
        onNexPageClick = { coroutineScope.launch { continuousReaderState.scrollScreenForward() } },
        onPrevPageClick = { coroutineScope.launch { continuousReaderState.scrollScreenBackward() } },
        contentAreaSize = areaSize,
        onTap = { offset ->
            if (!balloonsEnabled) {
                return@ReaderControlsOverlay false
            }
            if (indexInProgress) {
                return@ReaderControlsOverlay true
            }
            if (balloonsDetecting) {
                return@ReaderControlsOverlay true
            }
            val handled = balloonsState.handleTap(offset, areaSize, balloonDirection)
            if (handled) {
                return@ReaderControlsOverlay true
            }
            hasBalloons
        },
        onLongPress = { offset ->
            if (!balloonsEnabled || indexInProgress || balloonsDetecting) {
                return@ReaderControlsOverlay false
            }
            val hitBalloon = balloonsState.findBalloonAt(offset) ?: return@ReaderControlsOverlay false
            balloonsState.selectBalloon(hitBalloon)
            true
        },
        isSettingsMenuOpen = showSettingsMenu,
        onSettingsMenuToggle = { onShowSettingsMenuChange(!showSettingsMenu) },
        modifier = Modifier.onKeyEvent { event ->
            var consumed = true

            when (event.type) {
                KeyDown -> {
                    consumed = when (event.key) {
                        Key.DirectionLeft -> keysState.onLeftKeyDown()
                        Key.DirectionRight -> keysState.onRightKeyDown()
                        Key.DirectionDown -> keysState.onDownKeyDown()
                        Key.DirectionUp -> keysState.onUpKeyDown()
                        Key.VolumeUp -> keysState.onVolumeUpKeyDown()
                        Key.VolumeDown -> keysState.onVolumeDownKeyDown()
                        else -> false
                    }
                }

                KeyUp -> {
                    consumed = when (event.key) {
                        Key.MoveHome -> keysState.onScrollToFirstPage()
                        Key.MoveEnd -> keysState.onScrollToLastPage()
                        Key.V -> keysState.onReadingDirectionChange(TOP_TO_BOTTOM)
                        Key.L -> keysState.onReadingDirectionChange(LEFT_TO_RIGHT)
                        Key.R -> keysState.onReadingDirectionChange(RIGHT_TO_LEFT)
                        Key.DirectionDown -> keysState.onDownKeyUp()
                        Key.DirectionUp -> keysState.onUpKeyUp()
                        Key.DirectionRight -> keysState.onRightKeyUp()
                        Key.DirectionLeft -> keysState.onLeftKeyUp(event.isAltPressed)
                        Key.VolumeUp -> keysState.onVolumeUpKeyUp()
                        Key.VolumeDown -> keysState.onVolumeDownKeyUp()
                        else -> false
                    }
                }
            }

            consumed
        }
    ) {
        ScalableContainer(continuousReaderState.screenScaleState) {
            ReaderPages(state = continuousReaderState)
        }
        if (balloonsEnabled && indexInProgress && indexTotal > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                        shape = MaterialTheme.shapes.medium
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Indexing Smart mode $indexProgress/$indexTotal",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        BalloonOverlay(
            balloonsState = balloonsState,
            balloonImage = null
        )
    }
}

@Composable
private fun ReaderPages(state: ContinuousReaderState) {
    val pageIntervals = state.pageIntervals.collectAsState().value
    if (pageIntervals.isEmpty()) return
    val sidePadding = with(LocalDensity.current) { state.sidePaddingPx.collectAsState().value.toDp() }
    val readingDirection = state.readingDirection.collectAsState().value
    when (readingDirection) {
        TOP_TO_BOTTOM -> VerticalLayout(
            state = state,
            pageIntervals = pageIntervals,
            sidePadding = sidePadding
        )

        LEFT_TO_RIGHT -> HorizontalLayout(
            state = state,
            pageIntervals = pageIntervals,
            sidePadding = sidePadding,
            reversed = false
        )

        RIGHT_TO_LEFT -> HorizontalLayout(
            state = state,
            pageIntervals = pageIntervals,
            sidePadding = sidePadding,
            reversed = true
        )
    }
}

@Composable
private fun VerticalLayout(
    state: ContinuousReaderState,
    pageIntervals: List<BookPagesInterval>,
    sidePadding: Dp
) {
    LazyColumn(
        state = state.lazyListState,
        contentPadding = PaddingValues(start = sidePadding, end = sidePadding),
        userScrollEnabled = false,
    ) {
        continuousPagesLayout(pageIntervals) { page ->
            var displaySize by remember { mutableStateOf(state.guessPageDisplaySize(page)) }
            LaunchedEffect(Unit) {
                state.getPageDisplaySize(page).collect { displaySize = it }
            }
            val height = displaySize.height
            Column(
                modifier = Modifier
                    .animateContentSize(spring(stiffness = Spring.StiffnessVeryLow))
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceDim)
            ) {
                ContinuousReaderImage(
                    state = state,
                    page = page,
                    modifier = Modifier.height(with(LocalDensity.current) { height.toDp() })
                )
                Spacer(Modifier.height(state.pageSpacing.collectAsState().value.dp))
            }
        }

    }

    LaunchedEffect(Unit) { handlePageScrollEvents(state) }
}

@Composable
private fun HorizontalLayout(
    state: ContinuousReaderState,
    pageIntervals: List<BookPagesInterval>,
    sidePadding: Dp,
    reversed: Boolean
) {
    LazyRow(
        state = state.lazyListState,
        contentPadding = PaddingValues(top = sidePadding, bottom = sidePadding),
        userScrollEnabled = false,
        reverseLayout = reversed
    ) {
        continuousPagesLayout(pageIntervals) { page ->
            var displaySize by remember { mutableStateOf(state.guessPageDisplaySize(page)) }
            LaunchedEffect(Unit) {
                state.getPageDisplaySize(page).collect { displaySize = it }
            }
            val width = displaySize.width
            Row(
                Modifier
                    .animateContentSize(spring(stiffness = Spring.StiffnessVeryLow))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceDim)
            ) {
                ContinuousReaderImage(
                    state = state,
                    page = page,
                    modifier = Modifier.width(with(LocalDensity.current) { width.toDp() })
                )
                Spacer(Modifier.width(state.pageSpacing.collectAsState().value.dp))
            }

        }
    }

    LaunchedEffect(Unit) { handlePageScrollEvents(state) }
}

private fun LazyListScope.continuousPagesLayout(
    pageIntervals: List<BookPagesInterval>,
    pageContent: @Composable (PageMetadata) -> Unit,
) {
    item {
        Box(
            modifier = Modifier.sizeIn(minHeight = 300.dp, minWidth = 300.dp).fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Reached the start of the series", style = MaterialTheme.typography.titleLarge)
        }
    }
    pageIntervals.forEachIndexed { index, interval ->
        if (index != 0) {
            item {
                Column(
                    modifier = Modifier.sizeIn(minHeight = 300.dp, minWidth = 300.dp).fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    pageIntervals.getOrNull(index - 1)?.let { previous ->
                        Column {
                            Text("Previous:", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                previous.book.metadata.title,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                    Spacer(Modifier.size(50.dp))
                    Column {
                        Text("Current:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            interval.book.metadata.title,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }
        }
        items(interval.pages, key = { it }) { page -> pageContent(page) }
    }

    item {
        Box(
            modifier = Modifier.sizeIn(minHeight = 300.dp, minWidth = 300.dp).fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { Text("Reached the end of the series", style = MaterialTheme.typography.titleLarge) }
    }

}

private suspend fun handlePageScrollEvents(state: ContinuousReaderState) {
    var previousFistPage = state.lazyListState.layoutInfo.visibleItemsInfo
        .first { it.key is PageMetadata }.key as PageMetadata
    var previousLastPage = state.lazyListState.layoutInfo.visibleItemsInfo
        .last { it.key is PageMetadata }.key as PageMetadata

    snapshotFlow { state.lazyListState.layoutInfo }.collect { layout ->
        val firstPage = layout.visibleItemsInfo.first { it.key is PageMetadata }.key as PageMetadata
        val lastPage = layout.visibleItemsInfo.last { it.key is PageMetadata }.key as PageMetadata

        when {
            previousFistPage.bookId != firstPage.bookId -> state.onCurrentPageChange(firstPage)
            previousLastPage.bookId != lastPage.bookId -> state.onCurrentPageChange(lastPage)

            // scrolled back
            previousFistPage.pageNumber > firstPage.pageNumber -> state.onCurrentPageChange(firstPage)

            // scrolled through more than 1 item (possible navigation jump)
            (firstPage.pageNumber - previousFistPage.pageNumber) > 2 -> state.onCurrentPageChange(firstPage)

            // scrolled forward
            previousLastPage.pageNumber < lastPage.pageNumber -> state.onCurrentPageChange(lastPage)

            else -> return@collect
        }

        previousFistPage = firstPage
        previousLastPage = lastPage
    }
}

@Composable
private fun ContinuousReaderImage(
    state: ContinuousReaderState,
    page: PageMetadata,
    modifier: Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var imageResult by remember { mutableStateOf<ReaderImageResult?>(null) }
    DisposableEffect(Unit) {
        coroutineScope.launch {
            val result = state.getImage(page)
            result.image?.let { state.onPageDisplay(page, it) }
            imageResult = result
        }

        onDispose {
            imageResult?.image?.let { state.onPageDispose(page) }
        }
    }
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) { ReaderImageContent(imageResult) }
}


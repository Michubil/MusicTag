package dev.androidgui.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import dev.androidgui.core.designsystem.icon.AppIcons
import dev.androidgui.core.designsystem.icon.AppIconView
import dev.androidgui.core.designsystem.tokens.AppDimensions
import dev.androidgui.core.designsystem.tokens.AppIconSize
import dev.androidgui.core.designsystem.tokens.AppSpacing
import kotlin.math.roundToInt

@Immutable
data class AppAlbumCard(
    val id: String,
    val title: String,
    val summary: String,
    val artwork: ImageBitmap?,
    val indexLetter: String,
)

private val IndexLetters = ('A'..'Z').map(Char::toString) + "#"

@Composable
fun AppAlbumGrid(
    albums: List<AppAlbumCard>,
    minColumns: Int,
    onAlbumClick: (String) -> Unit,
) {
    AppAlbumGrid(albums, minColumns, onAlbumClick) { it.artwork }
}

@Composable
fun AppAlbumGrid(
    albums: List<AppAlbumCard>,
    minColumns: Int,
    onAlbumClick: (String) -> Unit,
    state: LazyGridState = rememberLazyGridState(),
    artwork: @Composable (AppAlbumCard) -> ImageBitmap?,
) {
    val letterTargets = remember(albums) { buildAlbumIndexTargets(albums) }
    val bottomPadding = LocalAppContentBottomPadding.current
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(minColumns.coerceIn(2, 4)),
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(PaddingValues(bottom = bottomPadding)),
            contentPadding = PaddingValues(
                start = AppDimensions.ScreenHorizontalPadding,
                top = AppDimensions.ContentVerticalPadding,
                end = AppDimensions.AlbumIndexWidth + AppSpacing.ExtraSmall,
                bottom = AppDimensions.ContentVerticalPadding + bottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Large),
        ) {
            itemsIndexed(albums, key = { _, album -> album.id }) { _, album ->
                AlbumCell(album, artwork(album), onAlbumClick)
            }
        }
        if (albums.isNotEmpty()) {
            AlbumIndexScrollbar(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = AppDimensions.ContentVerticalPadding,
                        bottom = AppDimensions.ContentVerticalPadding + bottomPadding,
                    ),
                onLetterSelected = { letter ->
                    letterTargets[letter]?.let { state.requestScrollToItem(it) }
                },
            )
        }
    }
}

@Composable
private fun AlbumIndexScrollbar(modifier: Modifier, onLetterSelected: (String) -> Unit) {
    var activeIndex by remember { mutableStateOf<Int?>(null) }
    val selectLetter by rememberUpdatedState(onLetterSelected)
    BoxWithConstraints(modifier) {
        val railHeight = minOf(maxHeight, AppDimensions.AlbumIndexLetterHeight * IndexLetters.size)
        val density = LocalDensity.current
        val railHeightPx = with(density) { railHeight.toPx() }
        val indicatorSizePx = with(density) { AppDimensions.AlbumIndexIndicatorSize.toPx() }
        val availableHeightPx = with(density) { maxHeight.toPx() }
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(AppDimensions.AlbumIndexWidth)
                .height(railHeight)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // Own the full gesture before the individual accessible buttons see it.
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        fun selectAt(y: Float) {
                            if (size.height <= 0) return
                            val index = (y / size.height * IndexLetters.size)
                                .toInt().coerceIn(IndexLetters.indices)
                            if (activeIndex != index) {
                                activeIndex = index
                                selectLetter(IndexLetters[index])
                            }
                        }
                        down.consume()
                        try {
                            selectAt(down.position.y)
                            drag(down.id) { change ->
                                change.consume()
                                selectAt(change.position.y)
                            }
                        } finally {
                            activeIndex = null
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IndexLetters.forEachIndexed { index, letter ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .semantics(mergeDescendants = true) {
                            contentDescription = letter
                            selected = activeIndex == index
                        }
                        .clickable(role = Role.Button) { selectLetter(letter) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = letter,
                        color = if (activeIndex == index) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
        activeIndex?.let { index ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = AppDimensions.AlbumIndexWidth + AppSpacing.Small)
                    .offset {
                        val centerY = (availableHeightPx - railHeightPx) / 2f +
                            railHeightPx * (index + 0.5f) / IndexLetters.size
                        val top = (centerY - indicatorSizePx / 2f)
                            .coerceIn(0f, (availableHeightPx - indicatorSizePx).coerceAtLeast(0f))
                        IntOffset(0, top.roundToInt())
                    }
                    .size(AppDimensions.AlbumIndexIndicatorSize)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = IndexLetters[index],
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
        }
    }
}

@Composable
private fun AlbumCell(album: AppAlbumCard, artwork: ImageBitmap?, onAlbumClick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = { onAlbumClick(album.id) }),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) {
                Image(artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                AppIconView(AppIcons.Album, null, Modifier.size(AppIconSize.Standard))
            }
        }
        Text(
            text = album.title,
            modifier = Modifier.padding(top = AppSpacing.ExtraSmall),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = album.summary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun buildAlbumIndexTargets(albums: List<AppAlbumCard>): Map<String, Int> {
    val firstPositions = mutableMapOf<String, Int>()
    albums.forEachIndexed { index, album -> firstPositions.putIfAbsent(album.indexLetter, index) }
    var nextIndex = albums.lastIndex
    return buildMap {
        IndexLetters.asReversed().forEach { letter ->
            nextIndex = firstPositions[letter] ?: nextIndex
            if (nextIndex >= 0) put(letter, nextIndex)
        }
    }
}

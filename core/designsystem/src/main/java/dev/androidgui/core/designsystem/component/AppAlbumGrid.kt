package dev.androidgui.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.androidgui.core.designsystem.icon.AppIcons
import dev.androidgui.core.designsystem.icon.AppIconView
import dev.androidgui.core.designsystem.tokens.AppDimensions
import dev.androidgui.core.designsystem.tokens.AppIconSize
import dev.androidgui.core.designsystem.tokens.AppSpacing
import kotlinx.coroutines.launch

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
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val bottomPadding = LocalAppContentBottomPadding.current
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(minColumns.coerceIn(2, 4)),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(PaddingValues(bottom = bottomPadding)),
            contentPadding = PaddingValues(
                start = AppDimensions.ScreenHorizontalPadding,
                top = AppDimensions.ContentVerticalPadding,
                end = AppDimensions.ScreenHorizontalPadding + AppDimensions.IconContainer,
                bottom = AppDimensions.ContentVerticalPadding + bottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Large),
        ) {
            itemsIndexed(albums, key = { _, album -> album.id }) { _, album ->
                AlbumCell(album, onAlbumClick)
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(AppDimensions.IconContainer)
                .padding(vertical = AppDimensions.ContentVerticalPadding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IndexLetters.forEach { letter ->
                Text(
                    text = letter,
                    modifier = Modifier
                        .semantics { contentDescription = letter }
                        .clickable(role = Role.Button) {
                            scope.launch { scrollToLetter(gridState, albums, letter) }
                        }
                        .padding(vertical = AppSpacing.ExtraSmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun AlbumCell(album: AppAlbumCard, onAlbumClick: (String) -> Unit) {
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
            if (album.artwork != null) {
                Image(album.artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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

private fun letterRank(letter: String): Int = if (letter == "#") 26 else letter.first() - 'A'

private suspend fun scrollToLetter(state: LazyGridState, albums: List<AppAlbumCard>, letter: String) {
    val rank = letterRank(letter)
    val index = albums.indexOfFirst { letterRank(it.indexLetter) >= rank }
    if (index >= 0) state.scrollToItem(index)
}

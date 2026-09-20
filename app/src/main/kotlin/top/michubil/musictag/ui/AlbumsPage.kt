package top.michubil.musictag.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.androidgui.core.designsystem.component.AppAlbumCard
import dev.androidgui.core.designsystem.component.AppAlbumGrid
import dev.androidgui.core.designsystem.component.AppLoadingStatus
import dev.androidgui.core.designsystem.component.AppRefreshableContent
import dev.androidgui.core.designsystem.component.AppRefreshableContentList
import dev.androidgui.core.designsystem.component.EmptyState
import dev.androidgui.core.designsystem.component.ErrorState
import top.michubil.musictag.data.AlbumGroup
import top.michubil.musictag.data.AlbumLibrary
import top.michubil.musictag.data.AlbumSort

@Composable
fun AlbumsPage(state: MainUiState, onAction: (MainAction) -> Unit) {
    LaunchedEffect(state.root?.uri) { onAction(MainAction.AlbumsShown) }
    when {
        state.showStoragePicker -> AppRefreshableContentList(refreshing = false, enabled = false, onRefresh = {}) {
            item { SelectMusicFolderEmpty(state, onAction) }
        }
        !state.storageGranted -> AppRefreshableContent(
            refreshing = false,
            enabled = false,
            onRefresh = {},
            status = {
                AppLoadingStatus(message = state.loadingMessage, deferDisplay = true)
            },
        ) {}
        state.storageError != null -> AppRefreshableContentList(
            refreshing = false,
            enabled = state.canRefreshLibrary,
            onRefresh = { onAction(MainAction.RebuildLibrary) },
        ) {
            item {
                ErrorState(
                    title = "无法读取专辑",
                    message = state.storageError,
                    actionLabel = "重试",
                    onAction = { onAction(MainAction.RebuildLibrary) },
                )
            }
        }
        state.libraryReady && !state.libraryLoading && state.albums.isEmpty() -> AppRefreshableContentList(
            refreshing = state.libraryRefreshing,
            enabled = state.canRefreshLibrary,
            onRefresh = { onAction(MainAction.RebuildLibrary) },
        ) {
            item { EmptyState(title = "没有找到专辑") }
        }
        else -> AppRefreshableContent(
            refreshing = state.libraryRefreshing,
            enabled = state.canRefreshLibrary,
            onRefresh = { onAction(MainAction.RebuildLibrary) },
            status = if (state.storageGranted && (state.libraryLoading || !state.libraryReady) && !state.libraryRefreshing && state.albums.isEmpty()) {
                {
                    AppLoadingStatus(
                        message = state.libraryLoadingMessage,
                        progress = state.libraryProgress?.takeIf { it.total > 0 }?.let { it.completed.toFloat() / it.total },
                        deferDisplay = true,
                    )
                }
            } else null,
        ) {
            val cards = remember(state.albums) { albumCards(state.albums) }
            val gridState = rememberLazyGridState()
            var previousSort by remember(gridState) { mutableStateOf(state.displayedAlbumSort) }
            SideEffect {
                if (previousSort != state.displayedAlbumSort) {
                    // Reset alongside the published order, overriding lazy-grid key anchoring.
                    gridState.requestScrollToItem(0)
                    previousSort = state.displayedAlbumSort
                }
            }
            AppAlbumGrid(
                albums = cards,
                minColumns = state.albumMinColumns,
                onAlbumClick = { onAction(MainAction.OpenAlbum(it)) },
                state = gridState,
                artwork = { card -> albumCover(state.albumCovers[card.id], onAction) },
            )
        }
    }
}

private fun albumCards(albums: List<AlbumGroup>): List<AppAlbumCard> = albums.map { album ->
    AppAlbumCard(
        id = album.key,
        title = album.title,
        summary = AlbumLibrary.summary(album.tracks.size, album.year, album.artist),
        artwork = null,
        indexLetter = album.indexLetter,
    )
}

@Composable
private fun albumCover(cover: FileItem?, onAction: (MainAction) -> Unit): ImageBitmap? {
    if (cover == null) return null
    val preview by cover.preview.collectAsStateWithLifecycle()
    DisposableEffect(cover) {
        onAction(MainAction.LoadFilePreview(cover))
        onDispose { onAction(MainAction.ReleaseArtwork(cover)) }
    }
    return preview.artwork?.asImageBitmap()
}

@Composable
fun AlbumTracksPage(state: MainUiState, onAction: (MainAction) -> Unit) {
    AppRefreshableContentList(
        refreshing = state.libraryRefreshing,
        enabled = state.canRefreshLibrary,
        onRefresh = { onAction(MainAction.RebuildLibrary) },
        compact = true,
        status = if (state.libraryLoading && !state.libraryRefreshing) {
            {
                AppLoadingStatus(
                    message = state.libraryLoadingMessage,
                    progress = state.libraryProgress?.takeIf { it.total > 0 }?.let { it.completed.toFloat() / it.total },
                    deferDisplay = true,
                )
            }
        } else null,
    ) {
        if (state.albumItems.isEmpty() && !state.libraryLoading) {
            item { EmptyState(title = "这张专辑里没有歌曲") }
        } else {
            items(state.albumItems, key = { it.document.uri }) { item ->
                AudioFileRow(item, state, onAction, loadPreviews = true)
            }
        }
    }
}

internal val AlbumSort.label: String
    get() = when (this) {
        AlbumSort.TITLE -> "专辑名"
        AlbumSort.YEAR -> "年份"
        AlbumSort.COUNT -> "数量"
    }

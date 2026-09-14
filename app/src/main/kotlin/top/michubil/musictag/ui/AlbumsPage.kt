package top.michubil.musictag.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    LaunchedEffect(Unit) { onAction(MainAction.AlbumsShown) }
    when {
        state.showStoragePicker -> AppRefreshableContentList(refreshing = false, enabled = false, onRefresh = {}) {
            item { SelectMusicFolderEmpty(state, onAction) }
        }
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
        !state.libraryLoading && state.albums.isEmpty() -> AppRefreshableContentList(
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
            AppAlbumGrid(
                albums = albumCards(state, onAction),
                minColumns = state.albumMinColumns,
                onAlbumClick = { onAction(MainAction.OpenAlbum(it)) },
            )
        }
    }
}

@Composable
private fun albumCards(state: MainUiState, onAction: (MainAction) -> Unit): List<AppAlbumCard> =
    state.albums.map { album ->
        key(album.key) { albumCard(album, state.albumCovers[album.key], onAction) }
    }

@Composable
private fun albumCard(album: AlbumGroup, cover: FileItem?, onAction: (MainAction) -> Unit): AppAlbumCard {
    val artwork = if (cover != null) {
        val preview by cover.preview.collectAsStateWithLifecycle()
        DisposableEffect(cover) {
            onAction(MainAction.LoadFilePreview(cover))
            onDispose { onAction(MainAction.ReleaseArtwork(cover)) }
        }
        preview.artwork?.asImageBitmap()
    } else {
        null
    }
    return AppAlbumCard(
        id = album.key,
        title = album.title,
        summary = AlbumLibrary.summary(album.tracks.size, album.year, album.artist),
        artwork = artwork,
        indexLetter = AlbumLibrary.indexLetter(album.title),
    )
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
        AlbumSort.TITLE -> "标题"
        AlbumSort.YEAR -> "年份"
        AlbumSort.COUNT -> "数量"
    }

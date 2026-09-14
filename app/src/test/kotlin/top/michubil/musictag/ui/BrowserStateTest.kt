package top.michubil.musictag.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.ScanProgress
import top.michubil.musictag.data.rename.RenameEntry
import top.michubil.musictag.data.rename.RenamePreset
import top.michubil.musictag.data.storage.MusicDocument

class BrowserStateTest {
    private val savedTree = "content://provider/tree/music"

    @Test
    fun scanProgressExplainsLoadingAndBlocksSelectionAndWrites() {
        val ready = MainUiState(storageGranted = true, loading = false, selected = setOf("song"))
        assertTrue(ready.canSelectFiles)
        val scanning = ready.copy(loading = true, scanProgress = top.michubil.musictag.data.ScanProgress(16, 497))
        org.junit.jupiter.api.Assertions.assertEquals("正在筛选音频 16 / 497", scanning.loadingMessage)
        assertFalse(scanning.canSelectFiles)
        assertFalse(scanning.canEditSelection)
        assertFalse(scanning.canRefresh)
        assertTrue(scanning.canOpenDirectories)
        assertFalse(ready.copy(loading = true).canOpenDirectories)
        assertFalse(scanning.copy(busy = true).canOpenDirectories)
        assertFalse(scanning.copy(recoveryError = "待恢复").canOpenDirectories)
        assertFalse(scanning.copy(storageError = "读取失败").canOpenDirectories)
        assertFalse(scanning.copy(storageGranted = false).canOpenDirectories)
    }

    @Test
    fun renameDefaultsToTitleThenArtistWithoutSpacesAroundTheHyphen() {
        val state = MainUiState()

        assertEquals(RenamePreset.TITLE_ARTIST, state.renamePreset)
        assertEquals("@1-@2", state.renamePattern)
        assertEquals("@1-@2", state.renameCustomPattern)
    }

    @Test
    fun fileProcessingMessageIncludesCompletedAndTotalTaskCounts() {
        assertEquals("正式处理文件(0/490)", MainUiState(fileProgress = ScanProgress(0, 490)).processingMessage)
        assertEquals("正式处理文件(490/490)", MainUiState(fileProgress = ScanProgress(490, 490)).processingMessage)
    }

    @Test
    fun tagSavingRequiresValidLoadedChangesAndAvailableStorage() {
        val editor = TagEditorState(count = 1,
            draft = top.michubil.musictag.data.edit.TagDraft(changed = setOf(top.michubil.musictag.data.model.MetadataField.TITLE)))
        val ready = MainUiState(storageGranted = true, loading = false, selected = setOf("song"), editor = editor)
        assertTrue(ready.canSaveTags)
        listOf(ready.copy(editor = editor.copy(count = 0)), ready.copy(editor = editor.copy(loading = true)),
            ready.copy(editor = editor.copy(coverLoading = true)), ready.copy(editor = editor.copy(draft = top.michubil.musictag.data.edit.TagDraft())),
            ready.copy(editor = editor.copy(validation = "日期无效")), ready.copy(editor = editor.copy(error = "读取失败")),
            ready.copy(storageError = "授权失效"), ready.copy(recoveryError = "待恢复"),
            ready.copy(busy = true), ready.copy(selected = emptySet())).forEach { assertFalse(it.canSaveTags) }
    }

    @Test
    fun editingRequiresSelectionAndRenamingRequiresAValidLoadedPreview() {
        val document = MusicDocument(savedTree, "song", "parent", "old.mp3")
        val ready = MainUiState(storageGranted = true, loading = false, selected = setOf(document.uri))
        assertTrue(ready.canEditSelection)
        assertFalse(ready.canRename)
        val preview = ready.copy(renameEntries = listOf(RenameEntry(document, "new.mp3")))
        assertTrue(preview.canRename)
        listOf(preview.copy(busy = true), preview.copy(loading = true), preview.copy(renameLoading = true),
            preview.copy(renameError = "模板错误"), preview.copy(storageError = "权限失效"),
            preview.copy(recoveryError = "待恢复"), preview.copy(selected = emptySet()),
            preview.copy(storageGranted = false)).forEach { assertFalse(it.canRename) }
    }

    @Test
    fun coldStartDoesNotShowPickerBeforeCheckingSavedGrant() {
        val initial = MainUiState(treeUri = savedTree)
        assertTrue(initial.loading)
        assertFalse(initial.showStoragePicker)
        assertFalse(initial.copy(storageGranted = true, loading = false).showStoragePicker)
    }

    @Test
    fun firstLaunchAndRevokedGrantShowPickerOnlyAfterLoadingFinishes() {
        assertFalse(MainUiState().showStoragePicker)
        assertTrue(MainUiState(loading = false).showStoragePicker)
        assertTrue(MainUiState(treeUri = savedTree, loading = false, storageError = "授权失效").showStoragePicker)
    }

    @Test
    fun emptyGrantedDirectorySupportsManualRefresh() {
        val empty = MainUiState(treeUri = savedTree, storageGranted = true, loading = false)
        assertTrue(empty.items.isEmpty())
        assertTrue(empty.canRefresh)
        assertFalse(empty.showStoragePicker)
    }

    @Test
    fun manualRefreshCannotOverlapLoadingWritingOrMissingGrant() {
        val ready = MainUiState(storageGranted = true, loading = false)
        assertFalse(ready.copy(loading = true, refreshing = true).canRefresh)
        assertFalse(ready.copy(busy = true).canRefresh)
        assertFalse(ready.copy(storageGranted = false).canRefresh)
    }

    @Test
    fun writingBlocksSortTheSameWayAsManualRefresh() {
        val ready = MainUiState(storageGranted = true, loading = false)
        assertTrue(ready.canSort)
        assertFalse(ready.copy(busy = true).canSort)
        assertFalse(ready.copy(loading = true).canSort)
        assertFalse(ready.copy(storageGranted = false).canSort)
        assertEquals(ready.copy(busy = true).canRefresh, ready.copy(busy = true).canSort)
    }

    @Test
    fun albumLibraryIsNotEmptyUntilTheFirstIndexFinishes() {
        val waiting = MainUiState(storageGranted = true, loading = false)
        assertFalse(waiting.libraryReady)
        assertTrue(waiting.albums.isEmpty())
        assertFalse(waiting.libraryLoading)
        val empty = waiting.copy(libraryReady = true)
        assertTrue(empty.libraryReady)
        assertTrue(empty.albums.isEmpty())
        assertFalse(empty.libraryLoading)
    }

    @Test
    fun cachedAlbumsAndSearchCannotEditBeforeValidationEvenIfTheRefreshFails() {
        val cached = MainUiState(storageGranted = true, loading = false, libraryReady = true,
            showingCachedLibrary = true, libraryLoading = true, selected = setOf("song"))
        for (page in listOf(cached.copy(searching = true), cached.copy(openedAlbumKey = "album"))) {
            assertFalse(page.canSelectFiles)
            assertFalse(page.canEditSelection)
            val failedRefresh = page.copy(libraryLoading = false)
            assertFalse(failedRefresh.canEditSelection)
            assertTrue(failedRefresh.canRefreshLibrary)
            assertTrue(failedRefresh.copy(showingCachedLibrary = false).canEditSelection)
        }
        assertTrue(cached.canSelectFiles)
    }

    @Test
    fun searchCanSelectWhileDirectoryStillLoading() {
        val root = MusicDocument(savedTree, savedTree, null, "Music", isDirectory = true)
        val searching = MainUiState(storageGranted = true, loading = true, searching = true, root = root, selected = setOf("song"))
        assertTrue(searching.canSearch)
        assertTrue(searching.canSelectFiles)
        assertTrue(searching.canEditSelection)
        assertTrue(searching.canSort)
        assertFalse(searching.copy(busy = true).canSearch)
        assertEquals(emptyList<FileItem>(), searching.visibleItems)
        val results = MainUiState(searching = true, searchItems = listOf(FileItem(MusicDocument(savedTree, "song", savedTree, "a.flac"))))
        assertEquals(results.searchItems, results.visibleItems)
    }

    @Test
    fun providerFailureOffersRefreshWhileBlockingWrites() {
        val ready = MainUiState(storageGranted = true, loading = false, selected = setOf("content://provider/song"))
        assertTrue(ready.canScrape)
        val unavailable = ready.copy(storageError = "提供方暂时不可用")
        assertTrue(unavailable.canRefresh)
        assertFalse(unavailable.showStoragePicker)
        assertFalse(unavailable.canScrape)
    }
}

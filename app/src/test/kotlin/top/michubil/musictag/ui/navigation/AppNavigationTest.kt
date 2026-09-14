package top.michubil.musictag.ui.navigation

import dev.androidgui.core.designsystem.component.AppPageMotion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.michubil.musictag.ui.MainUiState

class AppNavigationTest {
    @Test
    fun rootReselectionIsNoOp() {
        assertEquals(TabDecision.Stay, tabDecision(Routes.Files, Routes.Files))
        assertEquals(TabDecision.Stay, tabDecision(Routes.Settings, Routes.Settings))
    }

    @Test
    fun childReselectionReturnsToItsRoot() {
        listOf(Routes.Folder, Routes.Search, Routes.Options, Routes.Candidates, Routes.Rename, Routes.Editor).forEach {
            assertEquals(TabDecision.PopToRoot, tabDecision(it, Routes.Files))
        }
        assertEquals(TabDecision.PopToRoot, tabDecision(Routes.About, Routes.Settings))
    }

    @Test
    fun switchingFromChildrenDiscardsTheirStack() {
        assertEquals(TabDecision.SwitchRoot, tabDecision(Routes.Candidates, Routes.Settings))
        assertEquals(TabDecision.SwitchRoot, tabDecision(Routes.Settings, Routes.Files))
        assertEquals(TabDecision.SwitchRoot, tabDecision(Routes.About, Routes.Files))
    }

    @Test
    fun rootTransitionsKeepReferenceDirection() {
        assertEquals(AppPageMotion.TabForward, resolvePageMotion(Routes.Options, Routes.Settings))
        assertEquals(AppPageMotion.TabForward, resolvePageMotion(Routes.Files, Routes.About))
        assertEquals(AppPageMotion.TabBackward, resolvePageMotion(Routes.Settings, Routes.Files, true))
        assertEquals(AppPageMotion.TabBackward, resolvePageMotion(Routes.About, Routes.Files))
    }

    @Test
    fun secondaryTransitionsUseOpenAndCloseMotion() {
        assertEquals(AppPageMotion.Open, resolvePageMotion(Routes.Folder, Routes.Options))
        assertEquals(AppPageMotion.Open, resolvePageMotion(Routes.Settings, Routes.About))
        assertEquals(AppPageMotion.Close, resolvePageMotion(Routes.Candidates, Routes.Options, true))
        assertEquals(AppPageMotion.Close, resolvePageMotion(Routes.Folder, Routes.Folder, true))
        assertEquals(AppPageMotion.Close, resolvePageMotion(Routes.About, Routes.Settings, true))
    }

    @Test
    fun browserRootIsTheGrantAndFolderUrisStayOpaque() {
        val root = "content://provider/tree/42/document/42"
        val child = "content://provider/tree/42/document/not-a-path%3A音乐%2FA%20B"
        assertEquals(root, browserDirectoryUri(Routes.Files, root, child))
        assertEquals(child, browserDirectoryUri(Routes.Folder, root, child))
        assertEquals(null, browserDirectoryUri(Routes.Settings, root, child))
        assertEquals(null, browserDirectoryUri(Routes.About, root, child))
        assertEquals(null, browserDirectoryUri(Routes.Search, root, child))
        assertEquals(null, browserDirectoryUri(Routes.Files, null, child))
    }

    @Test
    fun searchStaysOnTheFilesTabAndDoesNotReloadADirectory() {
        assertTrue(isBrowserRoute(Routes.Search))
        assertFalse(isDirectoryRoute(Routes.Search))
        assertFalse(shouldLeaveSearch(Routes.Search))
        assertFalse(shouldLeaveSearch(Routes.Editor))
        assertTrue(shouldLeaveSearch(Routes.Files))
        assertTrue(shouldLeaveSearch(Routes.Settings))
        assertTrue(shouldLeaveSearch(Routes.About))
        assertFalse(shouldReturnToBrowser(Routes.Search, MainUiState()))
    }

    @Test
    fun restoredWorkflowWithoutInMemorySelectionReturnsToBrowser() {
        val restored = MainUiState()
        assertTrue(shouldReturnToBrowser(Routes.Options, restored))
        assertTrue(shouldReturnToBrowser(Routes.Candidates, restored))
        assertTrue(shouldReturnToBrowser(Routes.Rename, restored))
        assertTrue(shouldReturnToBrowser(Routes.Editor, restored))
        assertFalse(shouldReturnToBrowser(Routes.Files, restored))
        assertFalse(shouldReturnToBrowser(Routes.Folder, restored))
        assertFalse(shouldReturnToBrowser(Routes.Settings, restored))
        assertFalse(shouldReturnToBrowser(Routes.About, restored))
    }

    @Test
    fun activeSelectionAndPendingScrapeKeepTheirWorkflow() {
        val selected = MainUiState(selected = setOf("content://provider/tree/Music/document/song.flac"))
        assertFalse(shouldReturnToBrowser(Routes.Options, selected))
        assertFalse(shouldReturnToBrowser(Routes.Candidates, selected))
        assertFalse(shouldReturnToBrowser(Routes.Rename, selected))
        assertFalse(shouldReturnToBrowser(Routes.Editor, selected))
        val writing = selected.copy(selected = emptySet(), busy = true)
        assertFalse(shouldReturnToBrowser(Routes.Options, writing))
        assertFalse(shouldReturnToBrowser(Routes.Candidates, writing))
        assertFalse(shouldReturnToBrowser(Routes.Rename, writing))
        assertFalse(shouldReturnToBrowser(Routes.Editor, writing))
    }

    @Test
    fun migrationKeepsSelectionDefaultsAndDynamicColor() {
        val state = MainUiState()
        assertTrue(state.dynamicColor)
        assertTrue(state.formatLyricsTimeline)
        assertTrue(state.policies.values.all { it.enabled && it.overwrite })
    }
}

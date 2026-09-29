package top.michubil.musictag.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.androidgui.core.designsystem.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ErrorCollector
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real NavHost + back dispatcher, with geometry sampled during the production transitions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36])
class PredictiveBackDirectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val errors = ErrorCollector()
    private lateinit var nav: NavHostController
    private lateinit var parentId: String
    private lateinit var childId: String
    private val pages = mutableMapOf<String, LayoutCoordinates>()

    @Before
    fun setUp() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme(darkTheme = false) {
                nav = rememberNavController()
                MusicNavHost(nav) {
                    composable(Routes.Files) { Page(it.id) }
                    composable(Routes.Albums) { Page(it.id) }
                    composable(Routes.Album, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                        Page(it.id)
                    }
                    composable(Routes.Folder, arguments = listOf(navArgument("path") { type = NavType.StringType })) {
                        Page(it.id)
                    }
                }
            }
        }
        advance(1_000)
        compose.runOnIdle { nav.navigate(Routes.Albums) }
        advance(1_000)
    }

    @Test
    fun backDuringEntryMovesTheChildRightAndRevealsTheParentFromLeft() {
        for (entryMillis in listOf(32L, 80L, 160L, 320L, 600L)) {
            enterPage(entryMillis)
            assertBackDirection("entry=$entryMillis ms")
            commitBack()
        }
    }

    @Test
    fun cancelledBackCanBeStartedAgainBeforeItsRollbackFinishes() {
        for (rollbackMillis in listOf(16L, 64L, 160L, 600L)) {
            enterPage(1_000)
            startBack()
            progressBack(0.65f)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            advance(rollbackMillis)
            compose.runOnIdle { assertEquals(childId, nav.currentBackStackEntry?.id) }
            assertBackDirection("rollback=$rollbackMillis ms")
            commitBack()
        }
    }

    @Test
    fun sameRouteEntriesKeepBackDirectionDuringEntry() {
        compose.runOnIdle { nav.navigate("files/folder?path=parent") }
        advance(1_000)
        enterPage(80, "files/folder?path=child")
        assertBackDirection("same folder route")
        commitBack()
    }

    private fun enterPage(millis: Long, route: String = "albums/album?id=test") {
        compose.runOnIdle {
            parentId = requireNotNull(nav.currentBackStackEntry).id
            nav.navigate(route)
            childId = requireNotNull(nav.currentBackStackEntry).id
        }
        advance(millis)
        compose.runOnIdle { assertEquals(childId, nav.currentBackStackEntry?.id) }
    }

    private fun assertBackDirection(scenario: String) {
        startBack()
        progressBack(0.25f)
        val first = positions()
        progressBack(0.65f)
        val later = positions()
        println("$scenario: parent/child x: $first -> $later")
        errors.checkSucceeds {
            assertTrue("$scenario: child must move right: $first -> $later", later.second > first.second)
            assertTrue("$scenario: child must not exit left: $later", later.second >= 0f)
            assertTrue("$scenario: parent must enter from left: $later", later.first <= 0f)
        }
    }

    private fun startBack() {
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(event(0f))
        }
        advance(32)
    }

    private fun progressBack(progress: Float) {
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(event(progress))
        }
        advance(32)
    }

    private fun commitBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(1_000)
        compose.runOnIdle { assertEquals(parentId, nav.currentBackStackEntry?.id) }
    }

    private fun event(progress: Float) = BackEventCompat(0f, 100f, progress, BackEventCompat.EDGE_LEFT)

    private fun positions(): Pair<Float, Float> = compose.runOnIdle {
        val parent = pages.getValue(parentId)
        val child = pages.getValue(childId)
        check(parent.isAttached && child.isAttached)
        parent.positionInRoot().x to child.positionInRoot().x
    }

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    @Composable
    private fun Page(entryId: String) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { pages[entryId] = it })
    }
}

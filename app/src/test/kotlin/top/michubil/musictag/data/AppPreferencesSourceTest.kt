package top.michubil.musictag.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.michubil.musictag.data.model.MusicSource
import top.michubil.musictag.data.model.SourceSelection
import top.michubil.musictag.data.model.defaultSourceSelections
import top.michubil.musictag.data.model.sourceSelections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AppPreferencesSourceTest {
    @Test
    fun oldSingleSourceChoicesMigrateOnceAndKeepTheDisabledSourceOff() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        storage.edit().clear().putString("source_tags", "QQ_ONLY")
            .putString("source_lyrics", "QQ_ONLY").putString("source_cover", "QQ_ONLY").commit()

        assertEquals(sourceSelections(MusicSource.QQ), AppPreferences(context).state.value.scrapeSources.selections)
        assertTrue(storage.contains("sources_network"))
        assertFalse(storage.contains("source_tags"))

        storage.edit().putString("source_tags", "NETEASE_ONLY").commit()
        assertEquals(sourceSelections(MusicSource.QQ), AppPreferences(context).state.value.scrapeSources.selections)
    }

    @Test
    fun disablingEverySourceSurvivesARestart() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        val disabled = MusicSource.entries.map { SourceSelection(it, enabled = false) }

        AppPreferences(context).setSources(disabled)

        assertEquals(disabled, AppPreferences(context).state.value.scrapeSources.selections)
    }

    @Test
    fun oldCategorySwitchesMergeByUnionWithoutKeepingTheirOrder() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        storage.edit().clear().putString("sources_tags", "QQ=1,NETEASE=0")
            .putString("sources_lyrics", "QQ=0,NETEASE=1")
            .putString("sources_cover", "QQ=0,NETEASE=0").commit()

        assertEquals(defaultSourceSelections(), AppPreferences(context).state.value.scrapeSources.selections)
        assertFalse(storage.contains("sources_tags"))
        assertFalse(storage.contains("sources_lyrics"))
        assertFalse(storage.contains("sources_cover"))
    }

    @Test
    fun oldAllDisabledSettingsStayDisabled() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        storage.edit().clear().putString("sources_tags", "NETEASE=0,QQ=0")
            .putString("sources_lyrics", "NETEASE=0,QQ=0")
            .putString("sources_cover", "NETEASE=0,QQ=0").commit()

        assertTrue(AppPreferences(context).state.value.scrapeSources.enabled().isEmpty())
    }
}

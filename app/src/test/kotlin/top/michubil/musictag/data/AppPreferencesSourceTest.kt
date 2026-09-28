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
import top.michubil.musictag.data.model.MetadataGroup
import top.michubil.musictag.data.model.MusicSource
import top.michubil.musictag.data.model.SourceSelection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AppPreferencesSourceTest {
    @Test
    fun qqOnlyMigratesOnceAndClearsTheLegacyKey() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        storage.edit().clear().putString("source_tags", "QQ_ONLY").commit()
        val tags = AppPreferences(context).state.value.scrapeSources.tags
        assertEquals(MusicSource.QQ, tags.first().source)
        assertTrue(tags.first { it.source == MusicSource.QQ }.enabled)
        assertFalse(tags.first { it.source == MusicSource.NETEASE }.enabled)
        assertFalse(storage.contains("source_tags"))
        assertTrue(storage.getBoolean("sources_migrated", false))
        storage.edit().putString("source_tags", "NETEASE_ONLY").commit()
        assertEquals(MusicSource.QQ, AppPreferences(context).state.value.scrapeSources.tags.first().source)
    }

    @Test
    fun disablingEverySourceSurvivesARestart() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = AppPreferences(context)
        val disabled = MusicSource.entries.map { SourceSelection(it, enabled = false) }
        preferences.setSources(MetadataGroup.LYRICS, disabled)
        assertEquals(disabled, AppPreferences(context).state.value.scrapeSources.lyrics)
    }
}

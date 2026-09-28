package top.michubil.musictag.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.model.MusicSource
import top.michubil.musictag.data.model.SourceSelection
import top.michubil.musictag.data.model.defaultSourceSelections

class SourceSelectionsTest {
    @Test
    fun legacyPresetsMigrateWithoutErasingAnExplicitChoice() {
        assertEquals(defaultSourceSelections(), SourceSelections.migrate(null))
        assertEquals(defaultSourceSelections(), SourceSelections.migrate("NETEASE_FIRST"))
        assertEquals(defaultSourceSelections(), SourceSelections.migrate("QQ_FIRST"))
        assertTrue(SourceSelections.migrate("QQ_FIRST").all { it.enabled })
        val neteaseOnly = SourceSelections.migrate("NETEASE_ONLY")
        assertTrue(neteaseOnly.first { it.source == MusicSource.NETEASE }.enabled)
        assertFalse(neteaseOnly.first { it.source == MusicSource.QQ }.enabled)
        val qqOnly = SourceSelections.migrate("QQ_ONLY")
        assertTrue(qqOnly.first { it.source == MusicSource.QQ }.enabled)
        assertFalse(qqOnly.first { it.source == MusicSource.NETEASE }.enabled)
    }

    @Test
    fun allDisabledSelectionsRoundTripAndCorruptSettingsDoNotDecode() {
        val disabled = MusicSource.entries.map { SourceSelection(it, enabled = false) }
        assertEquals(disabled, SourceSelections.decode(SourceSelections.encode(disabled)))
        val duplicates = SourceSelections.decode("NETEASE=1,NETEASE=0,QQ=0,UNKNOWN=1")
        assertEquals(listOf(SourceSelection(MusicSource.NETEASE, true), SourceSelection(MusicSource.QQ, false)), duplicates)
        assertEquals(defaultSourceSelections(), SourceSelections.decode("QQ=1,NETEASE=1"))
        assertNull(SourceSelections.decode("NETEASE=2"))
        assertNull(SourceSelections.decode("@@@"))
        assertNull(SourceSelections.decode(""))
    }
}

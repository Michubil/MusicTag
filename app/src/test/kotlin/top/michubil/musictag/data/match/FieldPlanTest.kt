package top.michubil.musictag.data.match

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.model.FieldPolicy
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.ScrapedMetadata

class FieldPlanTest {
    private val options = ScrapeOptions(policies = setOf(MetadataField.TITLE, MetadataField.LYRICS)
        .associateWith { FieldPolicy() })
    private val identified = ScrapedMetadata(
        title = RemoteValue.Available("Song"), lyrics = RemoteValue.ConfirmedAbsent,
    )

    @Test
    fun confirmedMissingLyricsDoNotMakeAChangedSongPartial() {
        val plan = planWrite(identified, emptyMap(), false, options, emptyMap())
        assertEquals(ScrapeKind.COMPLETE, plan.disposition.kind)
        assertEquals(RemoteValue.Available("Song"), plan.metadata.title)
    }

    @Test
    fun nothingToWriteStaysUnchangedAndUnavailableCoverMakesAWritePartial() {
        val unchanged = planWrite(identified, mapOf(MetadataField.TITLE to "Song"), false, options, emptyMap())
        assertEquals(ScrapeKind.UNCHANGED, unchanged.disposition.kind)

        val withCover = options.copy(policies = options.policies + (MetadataField.COVER to FieldPolicy()))
        val partial = planWrite(identified, emptyMap(), false, withCover,
            mapOf(MetadataField.COVER to "未取得封面"))
        assertEquals(ScrapeKind.PARTIAL, partial.disposition.kind)
    }
}

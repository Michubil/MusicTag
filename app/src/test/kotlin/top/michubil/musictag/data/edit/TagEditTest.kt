package top.michubil.musictag.data.edit

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.model.*
import top.michubil.musictag.data.storage.MusicDocument

class TagEditTest {
    @Test
    fun textFieldsAreTheEditorSetAndExcludeCover() {
        assertEquals(
            listOf(MetadataField.TITLE, MetadataField.ARTISTS, MetadataField.ALBUM, MetadataField.DATE,
                MetadataField.TRACK, MetadataField.DISC, MetadataField.LYRICS),
            MetadataField.textFields,
        )
        assertEquals(MetadataField.entries.filter { it != MetadataField.COVER }.toSet(), MetadataField.textFields.toSet())
    }

    @Test
    fun mixedBatchStartsUnselectedAndPreservesOtherFields() {
        val sources = listOf(source("A", "甲"), source("B", "乙"))
        val draft = TagDraft.from(sources)
        assertEquals(setOf(MetadataField.TITLE, MetadataField.ARTISTS), draft.mixed)
        assertTrue(draft.changed.isEmpty())
        assertEquals("共同专辑", draft.text[MetadataField.ALBUM])
        val mutation = draft.copy(text = draft.text + (MetadataField.TITLE to "New"), changed = setOf(MetadataField.TITLE)).mutation()
        assertEquals(RemoteValue.Available("New"), mutation.metadata.title)
        assertEquals(RemoteValue.Unavailable, mutation.metadata.artists)
        assertEquals(RemoteValue.Unavailable, mutation.metadata.cover)
        assertFalse(mutation.options.policies.getValue(MetadataField.ARTISTS).enabled)
    }

    @Test
    fun explicitBlankDeletesButUncheckedBlankPreserves() {
        val mutation = TagDraft(text = mapOf(MetadataField.TITLE to "  "), changed = setOf(MetadataField.TITLE, MetadataField.COVER)).mutation()
        assertEquals(RemoteValue.ConfirmedAbsent, mutation.metadata.title)
        assertEquals(RemoteValue.ConfirmedAbsent, mutation.metadata.cover)
        assertEquals(RemoteValue.Unavailable, mutation.metadata.album)
        assertThrows(IllegalArgumentException::class.java) { TagDraft().mutation() }
    }

    @Test
    fun manualDatesRetainPrecisionAndRejectInvalidCalendarDates() {
        listOf("2026", "2026-09", "2024-02-29").forEach { value ->
            val mutation = single(MetadataField.DATE, value).mutation()
            assertEquals(value, (mutation.metadata.date as RemoteValue.Available).value.asTagValue())
        }
        listOf("0000", "2026-13", "2026-02-29", "26", "2026-9-5").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { single(MetadataField.DATE, value).mutation() }
        }
    }

    @Test
    fun artistLinesAndNumberTotalsAreExplicitAndLyricsStayUnformatted() {
        val mutation = TagDraft(
            text = mapOf(MetadataField.ARTISTS to "甲\n\n乙 / 丙", MetadataField.TRACK to "2/12", MetadataField.LYRICS to "[00:01.234]原文"),
            changed = setOf(MetadataField.ARTISTS, MetadataField.TRACK, MetadataField.LYRICS),
        ).mutation()
        assertEquals(RemoteValue.Available(listOf("甲", "乙 / 丙")), mutation.metadata.artists)
        assertEquals(RemoteValue.Available(TrackIndex(2, 12)), mutation.metadata.track)
        assertEquals(RemoteValue.Available("[00:01.234]原文"), mutation.metadata.lyrics)
        assertFalse(mutation.options.formatLyricsTimeline)
        listOf("0", "-1", "2/1", "1/0", "1/2/3", "1/").forEach {
            assertThrows(IllegalArgumentException::class.java) { single(MetadataField.TRACK, it).mutation() }
        }
    }

    private fun single(field: MetadataField, value: String) = TagDraft(text = mapOf(field to value), changed = setOf(field))
    private fun source(title: String, artist: String) = TagEditSource(
        MusicDocument("tree", title, "parent", "$title.flac"), "digest",
        TagValues(mapOf(MetadataField.TITLE to title, MetadataField.ARTISTS to artist, MetadataField.ALBUM to "共同专辑")),
    )
}

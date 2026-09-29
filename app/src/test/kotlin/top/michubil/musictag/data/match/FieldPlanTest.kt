package top.michubil.musictag.data.match

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.model.FieldPolicy
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.ReleaseDate
import top.michubil.musictag.data.model.TrackIndex

class FieldPlanTest {
    private val options = ScrapeOptions(policies = setOf(MetadataField.TITLE, MetadataField.LYRICS)
        .associateWith { FieldPolicy() })
    private val identified = ScrapedMetadata(
        title = RemoteValue.Available("Song"), lyrics = RemoteValue.ConfirmedAbsent,
    )

    @Test
    fun existingWrongLabelsRemainEligibleForOverwriteAndOnlyProtectedFieldsAreSkipped() {
        val existing = mapOf(MetadataField.TITLE to "Another song", MetadataField.LYRICS to "  ")
        val selected = ScrapeOptions(policies = mapOf(
            MetadataField.TITLE to FieldPolicy(overwrite = true),
            MetadataField.ARTISTS to FieldPolicy(overwrite = false),
            MetadataField.LYRICS to FieldPolicy(overwrite = false),
            MetadataField.COVER to FieldPolicy(overwrite = false),
            MetadataField.ALBUM to FieldPolicy(enabled = false),
        ))
        val requested = fieldsToFetch(selected, existing, hasCover = true, supported = MetadataField.entries.toSet())
        assertEquals(setOf(MetadataField.TITLE, MetadataField.ARTISTS, MetadataField.LYRICS),
            requested.policies.filterValues { it.enabled }.keys)
        val protected = fieldsToFetch(options.copy(policies = options.policies.mapValues {
            it.value.copy(overwrite = false)
        }), mapOf(MetadataField.TITLE to "Song", MetadataField.LYRICS to "Lyrics"), false, MetadataField.entries.toSet())
        assertEquals(emptySet<MetadataField>(), protected.policies.filterValues { it.enabled }.keys)
    }

    @Test
    fun unsupportedDiscDoesNotTriggerRequestsWhenTheOtherFieldsAreProtected() {
        val selected = ScrapeOptions(policies = mapOf(
            MetadataField.TITLE to FieldPolicy(overwrite = false), MetadataField.DISC to FieldPolicy(),
        ))
        val requested = fieldsToFetch(selected, mapOf(MetadataField.TITLE to "Song"), false,
            MetadataField.entries.toSet() - MetadataField.DISC)
        assertEquals(emptySet<MetadataField>(), requested.policies.filterValues { it.enabled }.keys)
    }

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

    @Test
    fun typedTextValuesKeepTheirTagFormattingWhenCheckingForChanges() {
        val metadata = ScrapedMetadata(
            artists = RemoteValue.Available(listOf(" Singer ", "Guest", "")),
            date = RemoteValue.Available(ReleaseDate(2026, 9, null)),
            track = RemoteValue.Available(TrackIndex(2, 12)),
            disc = RemoteValue.ConfirmedAbsent,
        )
        val selected = ScrapeOptions(policies = setOf(MetadataField.ARTISTS, MetadataField.DATE,
            MetadataField.TRACK, MetadataField.DISC).associateWith { FieldPolicy() })
        val existing = mapOf(MetadataField.ARTISTS to "Singer\nGuest", MetadataField.DATE to "2026-09",
            MetadataField.TRACK to "2/12", MetadataField.DISC to "1/2")
        val plan = planWrite(metadata, existing, false, selected, emptyMap())
        assertEquals(ScrapeKind.COMPLETE, plan.disposition.kind)
        assertEquals(ScrapedMetadata(disc = RemoteValue.ConfirmedAbsent), plan.metadata)
    }

    @Test
    fun unavailableFieldsWithoutWritesDoNotCreateAReviewOrFileEdit() {
        val failed = planWrite(ScrapedMetadata(), emptyMap(), false, options,
            mapOf(MetadataField.TITLE to "未取得标题"))
        assertEquals(ScrapeKind.FAILED, failed.disposition.kind)

        val unchanged = planWrite(ScrapedMetadata(title = RemoteValue.Available("Song")),
            mapOf(MetadataField.TITLE to "Song"), false, options,
            mapOf(MetadataField.LYRICS to "未取得歌词"))
        assertEquals(ScrapeKind.UNCHANGED, unchanged.disposition.kind)
    }
}

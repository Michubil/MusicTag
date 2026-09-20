package top.michubil.musictag.data.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.MetadataGroup
import top.michubil.musictag.data.model.MusicSource
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.model.ScrapeSources
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.model.SourceOrder

class MetadataSourcesClientTest {
    private fun order(
        sources: ScrapeSources = ScrapeSources(),
        forced: SongCandidate? = null,
        searchGroup: MetadataGroup = MetadataGroup.TAGS,
    ): (MetadataGroup) -> List<MusicSource> = { orderedSources(it, sources, forced, searchGroup) }

    private fun song(source: MusicSource) = SongCandidate(
        1, "title", listOf("artist"), "album", null, 1000L, null, null, 1, source,
    )

    @Test
    fun firstSourceBatchesAllSelectedGroupsInOneRequest() {
        val selected = setOf(MetadataField.TITLE, MetadataField.LYRICS, MetadataField.COVER)
        val fields = metadataRequestFields(
            source = MusicSource.NETEASE,
            required = setOf(MetadataField.TITLE),
            selected = selected,
            attempted = emptyMap(),
            downloaded = emptyMap(),
            order = order(),
        )
        assertEquals(selected, fields)
    }

    @Test
    fun unselectedFieldsAreNeverRequested() {
        val fields = metadataRequestFields(
            source = MusicSource.NETEASE,
            required = MetadataField.entries.toSet(),
            selected = setOf(MetadataField.TITLE),
            attempted = emptyMap(),
            downloaded = emptyMap(),
            order = order(),
        )
        assertEquals(setOf(MetadataField.TITLE), fields)
    }

    @Test
    fun alreadyAttemptedFieldsOnThisSourceAreNotRequestedAgain() {
        val fields = metadataRequestFields(
            source = MusicSource.NETEASE,
            required = setOf(MetadataField.TITLE, MetadataField.LYRICS),
            selected = setOf(MetadataField.TITLE, MetadataField.LYRICS),
            attempted = mapOf(MusicSource.NETEASE to setOf(MetadataField.TITLE)),
            downloaded = emptyMap(),
            order = order(),
        )
        assertEquals(setOf(MetadataField.LYRICS), fields)
    }

    @Test
    fun otherGroupsJoinTheBatchOnlyAfterEarlierSourcesHaveMissedThem() {
        val fields = metadataRequestFields(
            source = MusicSource.QQ,
            required = setOf(MetadataField.TITLE),
            selected = setOf(MetadataField.TITLE, MetadataField.LYRICS),
            attempted = mapOf(MusicSource.NETEASE to setOf(MetadataField.LYRICS)),
            downloaded = mapOf(MusicSource.NETEASE to ScrapedMetadata()),
            order = order(),
        )
        assertEquals(setOf(MetadataField.TITLE, MetadataField.LYRICS), fields)
    }

    @Test
    fun fallbackDoesNotPreemptAGroupEarlierSourcesHaveNotAttempted() {
        val fields = metadataRequestFields(
            source = MusicSource.QQ,
            required = setOf(MetadataField.TITLE),
            selected = setOf(MetadataField.TITLE, MetadataField.LYRICS),
            attempted = emptyMap(),
            downloaded = emptyMap(),
            order = order(),
        )
        assertEquals(setOf(MetadataField.TITLE), fields)
    }

    @Test
    fun availableValuesAreNotAddedAsEligibleFallbackFields() {
        val fields = metadataRequestFields(
            source = MusicSource.QQ,
            required = setOf(MetadataField.ARTISTS),
            selected = setOf(MetadataField.TITLE, MetadataField.ARTISTS),
            attempted = mapOf(MusicSource.NETEASE to setOf(MetadataField.TITLE, MetadataField.ARTISTS)),
            downloaded = mapOf(
                MusicSource.NETEASE to ScrapedMetadata(
                    title = RemoteValue.Available("t"),
                    artists = RemoteValue.Unavailable,
                ),
            ),
            order = order(),
        )
        assertEquals(setOf(MetadataField.ARTISTS), fields)
    }

    @Test
    fun confirmedAbsentCountsAsAMissForFallbackEligibility() {
        val fields = metadataRequestFields(
            source = MusicSource.QQ,
            required = emptySet(),
            selected = setOf(MetadataField.TITLE),
            attempted = mapOf(MusicSource.NETEASE to setOf(MetadataField.TITLE)),
            downloaded = mapOf(MusicSource.NETEASE to ScrapedMetadata(title = RemoteValue.ConfirmedAbsent)),
            order = order(),
        )
        assertEquals(setOf(MetadataField.TITLE), fields)
    }

    @Test
    fun sourceAbsentFromAGroupDoesNotTakeThatGroupsFieldsAsEligible() {
        val sources = ScrapeSources(tags = SourceOrder.NETEASE_ONLY, lyrics = SourceOrder.QQ_ONLY)
        val fields = metadataRequestFields(
            source = MusicSource.QQ,
            required = emptySet(),
            selected = MetadataField.entries.toSet(),
            attempted = emptyMap(),
            downloaded = emptyMap(),
            order = order(sources),
        )
        assertEquals(setOf(MetadataField.LYRICS), fields)
    }

    @Test
    fun forcedCandidateReordersOnlyTheSearchGroup() {
        val forced = song(MusicSource.QQ)
        val sources = ScrapeSources()
        assertEquals(
            listOf(MusicSource.QQ, MusicSource.NETEASE),
            orderedSources(MetadataGroup.TAGS, sources, forced, MetadataGroup.TAGS),
        )
        assertEquals(
            listOf(MusicSource.NETEASE, MusicSource.QQ),
            orderedSources(MetadataGroup.LYRICS, sources, forced, MetadataGroup.TAGS),
        )
    }
}

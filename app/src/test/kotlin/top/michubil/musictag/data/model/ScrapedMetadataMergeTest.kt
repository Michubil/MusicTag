package top.michubil.musictag.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScrapedMetadataMergeTest {
    private val values = listOf(
        RemoteValue.Available("keep"),
        RemoteValue.ConfirmedAbsent,
        RemoteValue.Unavailable,
    )

    @Test
    fun availableValuesWinAndConfirmedAbsenceSurvivesUnavailableFallback() {
        val kept = ScrapedMetadata(title = RemoteValue.Available("keep"))
            .merge(ScrapedMetadata(title = RemoteValue.Available("other")), setOf(MetadataField.TITLE))
        assertEquals(RemoteValue.Available("keep"), kept.title)
        for (first in values) {
            for (next in values) {
                val merged = ScrapedMetadata(title = first)
                    .merge(ScrapedMetadata(title = next), setOf(MetadataField.TITLE))
                val expected = when {
                    first is RemoteValue.Available -> first
                    next is RemoteValue.Available -> next
                    first == RemoteValue.ConfirmedAbsent || next == RemoteValue.ConfirmedAbsent ->
                        RemoteValue.ConfirmedAbsent
                    else -> RemoteValue.Unavailable
                }
                assertEquals(expected, merged.title, "first=$first next=$next")
            }
        }
    }

    @Test
    fun unselectedFieldsStayUntouched() {
        val merged = ScrapedMetadata(album = RemoteValue.Available("album"))
            .merge(ScrapedMetadata(album = RemoteValue.ConfirmedAbsent), setOf(MetadataField.TITLE))
        assertEquals(RemoteValue.Available("album"), merged.album)
    }
}

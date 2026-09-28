package top.michubil.musictag.data.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AcoustIdClientTest {
    @Test
    fun recordingSuggestionsKeepTheHighestScoreAndSkipUnusableRows() {
        val response = JSONObject("""{
            "results": [
                {"score": 0.6, "recordings": [{"title": "Song", "artists": [{"name": "Artist"}]}]},
                {"score": 0.9, "recordings": [{"title": "Song", "artists": [{"name": "Artist"}]}]},
                {"score": 0.8, "recordings": [{"title": "Other", "artists": []}, {}]},
                {"score": 1.5, "recordings": [{"title": "Invalid"}]}
            ]
        }""")
        assertEquals(
            listOf(FingerprintSuggestion("Song", listOf("Artist"), 0.9),
                FingerprintSuggestion("Other", emptyList(), 0.8)),
            AcoustIdClient.parseSuggestions(response),
        )
    }
}

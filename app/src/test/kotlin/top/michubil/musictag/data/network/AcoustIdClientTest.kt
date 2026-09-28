package top.michubil.musictag.data.network

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.michubil.musictag.data.fingerprint.AudioFingerprint
import top.michubil.musictag.data.model.CoverImage

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AcoustIdClientTest {
    @Test
    fun lookupEncodesMetadataFlagsAsSeparateFormValues() = runBlocking {
        val transport = object : MusicTransport {
            override suspend fun json(url: String, referer: String, label: String, body: ByteArray?,
                contentType: String): JSONObject {
                assertEquals("https://api.acoustid.org/v2/lookup", url)
                assertEquals("application/x-www-form-urlencoded", contentType)
                assertTrue(requireNotNull(body).toString(Charsets.UTF_8).contains("meta=recordings+compress"))
                return JSONObject("""{"status":"ok","results":[{"score":0.9,"recordings":[{"title":"Song","artists":[{"name":"Artist"}]}]}]}""")
            }

            override suspend fun cover(rawUrl: String, referer: String,
                trustedHost: (String) -> Boolean): CoverImage = error("No artwork request expected")
        }
        assertEquals(
            listOf(FingerprintSuggestion("Song", listOf("Artist"), 0.9)),
            AcoustIdClient(transport, "test-client").lookup(AudioFingerprint("fingerprint", 120)),
        )
    }

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

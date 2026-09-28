package top.michubil.musictag.data.match

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.MusicSource
import top.michubil.musictag.data.model.SongCandidate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ScriptIdentityTest {
    @Test
    fun netEaseTraditionalEditionMatchesTheSimplifiedTags() {
        val local = LocalTrack("好戏开场.wav", "好戏开场", listOf("楊尚峯"), "活侠传 游戏原声带", 146_601)
        val remote = SongCandidate(
            1, "好戲開場 (《活俠傳》遊戲配樂)", listOf("杨尚峯"), "《活俠傳》遊戲原聲帶",
            null, 146_600, null, null, null, MusicSource.NETEASE,
        )
        assertEquals(
            "fold ${ScriptForms.simplified("楊尚峯")} / ${ScriptForms.simplified("杨尚峯")}",
            identityKey("楊尚峯"), identityKey("杨尚峯"),
        )
        assertEquals(TitleRelation.VARIANT, titleRelation(local.title!!, remote.title))
        assertEquals(ArtistRelation.MATCH, artistRelation(local.artists, remote.artists, true))
        assertEquals(AlbumRelation.SAME, albumRelation(local.album, remote.album))
        val decision = RecordingMatch.decide(local, listOf(FoundCandidate(remote, 0, 0)), null)
        assertEquals(remote, (decision.outcome as MatchOutcome.Accept).candidate)
    }
}

package top.michubil.musictag.data.match

import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.SongCandidate

internal data class FoundCandidate(
    val candidate: SongCandidate,
    val platformRank: Int,
)

internal data class Decision(val ranked: List<SongCandidate>, val outcome: MatchOutcome)

internal object RecordingMatch {
    fun decide(track: LocalTrack, found: List<FoundCandidate>, user: UserQuery?): Decision {
        val ranked = found.sortedWith(displayOrder(track, user)).map(FoundCandidate::candidate)
        if (found.isEmpty()) return Decision(ranked, MatchOutcome.None("没有合适候选"))
        if (conflictingInputs(track, user)) return Decision(ranked, MatchOutcome.Review("标签和文件名指向不同歌曲"))
        val eligible = found.filter { canAccept(track, it.candidate, user) }
        if (eligible.isEmpty()) return Decision(ranked, MatchOutcome.Review(blockReason(track, found, user)))
        val anchor = eligible.sortedWith(displayOrder(track, user)).first()
        val anchorScore = rank(requireNotNull(bestEvidence(track, anchor.candidate, user)))
        val competing = eligible.any { item ->
            item.candidate.key != anchor.candidate.key && !sameRecording(anchor.candidate, item.candidate) &&
                rank(requireNotNull(bestEvidence(track, item.candidate, user))) == anchorScore
        }
        if (competing) return Decision(ranked, MatchOutcome.Review("存在其他可能的录音"))
        val identity = found.filter { supportsRecording(track, anchor.candidate, it.candidate) }.map { it.candidate }
        return Decision(ranked, MatchOutcome.Accept(anchor.candidate, selectRelease(identity, track.album), "标题和艺术家对应"))
    }

    fun canAccept(track: LocalTrack, candidate: SongCandidate, user: UserQuery?): Boolean {
        if (conflictingInputs(track, user)) return false
        val evidence = bestEvidence(track, candidate, user) ?: return false
        if (evidence.title != TitleRelation.EXACT && evidence.title != TitleRelation.VARIANT) return false
        if (evidence.artist != ArtistRelation.MATCH) return false
        if (!evidence.filenameConfirmed && readings(track, user).none { !it.requiresBoth && it.artistsTrusted }) {
            return false
        }
        if (evidence.version == VersionRelation.CONFLICT || evidence.version == VersionRelation.ONE_SIDED) return false
        if (evidence.duration == DurationRelation.CONFLICT) return false
        return true
    }

    fun needsDetail(track: LocalTrack, candidate: SongCandidate, user: UserQuery?): Boolean {
        val evidence = bestEvidence(track, candidate, user) ?: return false
        val titleMatches = evidence.title == TitleRelation.EXACT || evidence.title == TitleRelation.VARIANT
        if (!titleMatches) return false
        if (evidence.artist == ArtistRelation.INCOMPLETE || evidence.artist == ArtistRelation.CONFLICT) return true
        return candidate.durationMs == null || candidate.durationMs <= 0
    }

    fun supportsRecording(track: LocalTrack, anchor: SongCandidate, other: SongCandidate): Boolean {
        if (other.key == anchor.key) return true
        if (!sameRecording(anchor, other)) return false
        return durationRelation(track.durationMs, other.durationMs) != DurationRelation.CONFLICT
    }

    fun selectRelease(identity: List<SongCandidate>, localAlbum: String?): SongCandidate? {
        val named = identity.filter { identityKey(it.album).isNotEmpty() }.distinctBy { identityKey(it.album) }
        if (named.size == 1) return named.first()
        val local = localAlbum?.let(::identityKey)?.takeIf { it.isNotEmpty() } ?: return null
        return named.singleOrNull { identityKey(it.album) == local }
    }
}

private fun RecordingMatch.blockReason(track: LocalTrack, found: List<FoundCandidate>, user: UserQuery?): String {
    val evidence = found.mapNotNull { bestEvidence(track, it.candidate, user) }.maxWithOrNull(blockOrder) ?: return "证据不足"
    return when {
        evidence.version == VersionRelation.CONFLICT -> "版本冲突"
        evidence.duration == DurationRelation.CONFLICT -> "时长不符"
        evidence.version == VersionRelation.ONE_SIDED -> "版本待确认"
        evidence.artist == ArtistRelation.CONFLICT -> "署名不同"
        evidence.artist == ArtistRelation.INCOMPLETE -> "署名未完全对应"
        evidence.artist == ArtistRelation.UNKNOWN -> "只有标题对应，需要确认"
        evidence.title == TitleRelation.WEAK || evidence.title == TitleRelation.NONE -> "没有可靠的标题对应"
        else -> "证据不足"
    }
}

private fun displayOrder(track: LocalTrack, user: UserQuery?) = Comparator<FoundCandidate> { left, right ->
    val leftEvidence = bestEvidence(track, left.candidate, user)
    val rightEvidence = bestEvidence(track, right.candidate, user)
    compareValues(rightEvidence?.let { rank(it) }, leftEvidence?.let { rank(it) }).takeIf { it != 0 }
        ?: left.platformRank.compareTo(right.platformRank)
            .takeIf { it != 0 }
        ?: left.candidate.key.compareTo(right.candidate.key)
}

private fun rank(evidence: Evidence): Int {
    var score = 0
    if (evidence.title == TitleRelation.EXACT) score += 400 else if (evidence.title == TitleRelation.VARIANT) score += 300
    else if (evidence.title == TitleRelation.WEAK) score += 100
    if (evidence.artist == ArtistRelation.MATCH) score += 40 else if (evidence.artist == ArtistRelation.UNKNOWN) score += 20
    else if (evidence.artist == ArtistRelation.INCOMPLETE) score += 10
    if (evidence.duration == DurationRelation.CLOSE) score += 4
    if (evidence.version == VersionRelation.SAME || evidence.version == VersionRelation.UNSPECIFIED) score += 2
    if (evidence.album == AlbumRelation.SAME) score += 1
    if (evidence.version == VersionRelation.CONFLICT || evidence.duration == DurationRelation.CONFLICT) score -= 1000
    return score
}

private val blockOrder = compareBy<Evidence>(
    { it.title == TitleRelation.EXACT || it.title == TitleRelation.VARIANT },
    { it.artist == ArtistRelation.MATCH || it.artist == ArtistRelation.INCOMPLETE },
    { it.duration != DurationRelation.CONFLICT },
    { it.version != VersionRelation.CONFLICT },
)

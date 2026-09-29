package top.michubil.musictag.data

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.michubil.musictag.data.fingerprint.AudioFingerprinter
import top.michubil.musictag.data.lyrics.LyricsCodec
import top.michubil.musictag.data.match.CandidateSearch
import top.michubil.musictag.data.match.MatchResolver
import top.michubil.musictag.data.match.MatchSession
import top.michubil.musictag.data.match.ScrapeDisposition
import top.michubil.musictag.data.match.ScrapeKind
import top.michubil.musictag.data.match.UserQuery
import top.michubil.musictag.data.match.planWrite
import top.michubil.musictag.data.match.fieldsToFetch
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.map
import top.michubil.musictag.data.network.MetadataSourcesClient
import top.michubil.musictag.data.network.AcoustIdClient
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.storage.MusicDocument
import top.michubil.musictag.data.storage.canSafelyReplace
import top.michubil.musictag.data.network.NetEaseClient
import top.michubil.musictag.data.network.QqMusicClient

internal class MusicScraper(
    private val library: MusicLibrary,
    private val files: AudioFiles,
    val sources: MetadataSourcesClient = MetadataSourcesClient(NetEaseClient(), QqMusicClient()),
) {
    private val acoustId = AcoustIdClient()
    // Bounds source operations (each may fan out to several HTTP requests), not connections.
    private val sourceOperationSlots = Semaphore(LocalFileWork.parallelism)
    private val fingerprintSlots = Semaphore(LocalFileWork.parallelism)
    private val candidateArtworks = LruCache<String, Bitmap>(32)

    suspend fun candidates(
        document: MusicDocument,
        options: ScrapeOptions,
        query: UserQuery? = null,
    ): CandidateSearch {
        val track = files.withLocalCopy(document, previewOnly = true) { library.trackForMatching(document, it) }
        return sourceOperationSlots.withPermit { sources.candidates(track, options, query) }
    }

    suspend fun candidateArtwork(candidate: SongCandidate): Bitmap? {
        val key = "${candidate.key}:${candidate.albumId}:${candidate.coverUrl}"
        candidateArtworks.get(key)?.let { return it }
        val cover = operationResult {
            sourceOperationSlots.withPermit { sources.candidateCover(candidate) }
        }.getOrNull()
        return cover?.let { withContext(LocalFileWork.dispatcher) { decodeArtwork(it.bytes, 128) } }
            ?.also { candidateArtworks.put(key, it) }
    }

    suspend fun scrape(
        documents: List<MusicDocument>,
        options: ScrapeOptions,
        forcedCandidate: SongCandidate?,
        session: MatchSession?,
        onProgress: suspend (ScanProgress) -> Unit,
    ): List<Result<ScrapeDisposition>> = withContext(LocalFileWork.dispatcher) {
        val batchClient = sources.forBatch(this)
        LocalFileWork.map(documents, onProgress) { document ->
            operationResult {
                val activeSession = session?.takeIf { forcedCandidate != null && it.sameFile(document) }
                scrape(document, options, forcedCandidate, activeSession, batchClient)
            }
        }
    }

    private suspend fun scrape(
        document: MusicDocument,
        options: ScrapeOptions,
        forcedCandidate: SongCandidate?,
        session: MatchSession?,
        batchClient: MetadataSourcesClient,
    ): ScrapeDisposition {
        if (session != null && !session.sameFile(document)) {
            return ScrapeDisposition(ScrapeKind.FAILED, "文件已变化，请重新匹配")
        }
        val parent = files.directory(document.treeUri, requireNotNull(document.parentUri))
        require(canSafelyReplace(document, parent)) {
            "${document.name}：提供方不支持安全替换所需的创建、重命名和删除操作"
        }
        return files.withDigestedLocalCopy(document) { file, originalDigest ->
            val existing = AudioMetadataReader.readEditor(file, includeArtwork = false).tags
            val requested = fieldsToFetch(options, existing.text, existing.hasCover, batchClient.supportedFields(options.sources))
            if (requested.policies.values.none { it.enabled }) {
                return@withDigestedLocalCopy ScrapeDisposition(ScrapeKind.UNCHANGED)
            }
            val track = library.trackForMatching(document, file)
            val reusable = session?.takeIf { it.sources == options.sources && it.policies == options.policies }
            val search = if (forcedCandidate == null) {
                MatchResolver.resolve(
                    track = track,
                    search = { query -> sourceOperationSlots.withPermit { batchClient.candidates(track, requested, query) } },
                    recognize = {
                        acoustId.requireConfigured()
                        val fingerprint = fingerprintSlots.withPermit { AudioFingerprinter.calculate(file, track.durationMs) }
                        sourceOperationSlots.withPermit { acoustId.lookup(fingerprint) }
                    },
                )
            } else reusable?.search ?: sourceOperationSlots.withPermit {
                batchClient.relatedCandidates(track, requested, forcedCandidate)
            }
            val selection = MatchResolver.select(track, search, forcedCandidate)
                ?: return@withDigestedLocalCopy ScrapeDisposition(ScrapeKind.FAILED, search.outcome.summary)
            val prepared = sourceOperationSlots.withPermit {
                batchClient.metadata(track, requested, selection)
            }
            prepared.stop?.let { return@withDigestedLocalCopy it }
            val formatted = if (options.formatLyricsTimeline) {
                prepared.metadata.copy(lyrics = prepared.metadata.lyrics.map(LyricsCodec::formatTimeline))
            } else prepared.metadata
            val plan = planWrite(formatted, existing.text, existing.hasCover, requested, prepared.kept,
                prepared.unsupported)
            if (plan.disposition.kind != ScrapeKind.COMPLETE && plan.disposition.kind != ScrapeKind.PARTIAL) {
                return@withDigestedLocalCopy plan.disposition
            }
            files.editAndCommit(document, parent, originalDigest, file, plan.metadata, options)
            plan.disposition
        }
    }

}

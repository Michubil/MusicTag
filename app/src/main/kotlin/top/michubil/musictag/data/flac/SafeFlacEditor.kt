package top.michubil.musictag.data.flac

import top.michubil.musictag.data.io.AtomicAudioFileRewriter
import top.michubil.musictag.data.io.AtomicAudioFileRewriter.FileRegion
import top.michubil.musictag.data.model.CoverImage
import top.michubil.musictag.data.model.FieldPolicy
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.writeChange
import top.michubil.musictag.data.model.textValues
import java.io.File

class SafeFlacEditor {
    fun update(file: File, metadata: ScrapedMetadata, options: ScrapeOptions) {
        val source = FlacCodec.read(file)
        val blocks = mutateBlocks(source.blocks, metadata, options)
        if (blocks == source.blocks) return

        AtomicAudioFileRewriter.replacePreservingRegion(
            source = file,
            sourceRegion = FileRegion(source.audioOffset, file.length() - source.audioOffset),
            writeTemporary = { temporary ->
                FlacCodec.writeMetadata(temporary, blocks, file, source.audioOffset)
            },
            validateTemporary = { temporary ->
                val written = FlacCodec.read(temporary)
                require(written.blocks.contentEquals(blocks)) { "FLAC metadata validation failed" }
                FileRegion(written.audioOffset, temporary.length() - written.audioOffset)
            },
        )
    }

    internal fun mutateBlocks(
        original: List<FlacBlock>,
        metadata: ScrapedMetadata,
        options: ScrapeOptions,
    ): List<FlacBlock> {
        val oldComments = original.firstOrNull { it.type == VORBIS_COMMENT }
            ?.let { VorbisComments.decode(it.data) }
            ?: VorbisComments("Music Tag", emptyList())
        val replacements = linkedMapOf<String, List<String>>()
        val texts = metadata.textValues()
        flacStringKeys.forEach { (field, key) ->
            putStrings(replacements, key, oldComments, texts.getValue(field), options.policies.getValue(field))
        }
        putIndex(
            replacements,
            FlacKeys.TRACK,
            FlacKeys.TRACK_TOTAL,
            oldComments,
            metadata.track,
            options.policies.getValue(MetadataField.TRACK),
        )
        putIndex(
            replacements,
            FlacKeys.DISC,
            FlacKeys.DISC_TOTAL,
            oldComments,
            metadata.disc,
            options.policies.getValue(MetadataField.DISC),
        )

        val newComments = oldComments.replace(replacements).encode()
        val (newCover, deleteCover) = coverChange(
            original,
            metadata.cover,
            options.policies.getValue(MetadataField.COVER),
        )

        val result = mutableListOf<FlacBlock>()
        var commentWritten = false
        var coverWritten = false
        for (block in original) {
            when {
                block.type == VORBIS_COMMENT && !commentWritten -> {
                    result += FlacBlock(VORBIS_COMMENT, newComments)
                    commentWritten = true
                }
                block.type == PICTURE && isFrontCover(block) && newCover != null -> if (!coverWritten) {
                    result += FlacBlock(PICTURE, newCover)
                    coverWritten = true
                }
                block.type == PICTURE && isFrontCover(block) && deleteCover -> Unit
                block.type == PADDING && !commentWritten -> {
                    result += FlacBlock(VORBIS_COMMENT, newComments)
                    commentWritten = true
                    result += block
                }
                else -> result += block
            }
        }
        if (!commentWritten) result += FlacBlock(VORBIS_COMMENT, newComments)
        if (newCover != null && !coverWritten) {
            val padding = result.indexOfFirst { it.type == PADDING }
            result.add(if (padding < 0) result.size else padding, FlacBlock(PICTURE, newCover))
        }
        return if (result.contentEquals(original)) original else result
    }

    private fun putStrings(
        target: MutableMap<String, List<String>>,
        key: String,
        existing: VorbisComments,
        incoming: RemoteValue<List<String>>,
        policy: FieldPolicy,
    ) {
        policy.writeChange(incoming, existing.values(key).isNotEmpty(),
            write = { target[key] = it.filter(String::isNotBlank) },
            clear = { target[key] = emptyList() })
    }

    private fun coverChange(
        original: List<FlacBlock>,
        incoming: RemoteValue<CoverImage>,
        policy: FieldPolicy,
    ): Pair<ByteArray?, Boolean> {
        var cover: ByteArray? = null
        var delete = false
        policy.writeChange(incoming, original.any(::isFrontCover),
            write = { cover = FlacPicture.frontCover(it).encode() },
            clear = { delete = true })
        return cover to delete
    }

    private fun putIndex(
        target: MutableMap<String, List<String>>,
        numberKey: String,
        totalKey: String,
        existing: VorbisComments,
        incoming: RemoteValue<top.michubil.musictag.data.model.TrackIndex>,
        policy: FieldPolicy,
    ) {
        if (!policy.enabled) return
        when (incoming) {
            is RemoteValue.Available -> {
                if (policy.overwrite || existing.values(numberKey).isEmpty()) {
                    target[numberKey] = listOf(incoming.value.number.toString())
                }
                val total = incoming.value.total
                if (policy.overwrite || existing.values(totalKey).isEmpty()) {
                    target[totalKey] = total?.let { listOf(it.toString()) }.orEmpty()
                }
            }
            RemoteValue.ConfirmedAbsent -> if (policy.overwrite) {
                target[numberKey] = emptyList()
                target[totalKey] = emptyList()
            }
            RemoteValue.Unavailable -> Unit
        }
    }

    private fun isFrontCover(block: FlacBlock): Boolean = block.type == PICTURE &&
        runCatching { FlacPicture.decode(block.data).type == FRONT_COVER }.getOrDefault(false)

}

private fun List<FlacBlock>.contentEquals(other: List<FlacBlock>): Boolean =
    size == other.size && indices.all { index ->
        this[index].type == other[index].type && this[index].data.contentEquals(other[index].data)
    }

package top.michubil.musictag.data.id3

import top.michubil.musictag.data.io.AtomicAudioFileRewriter
import top.michubil.musictag.data.io.AtomicAudioFileRewriter.FileRegion
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.Mp3TagVersion
import java.io.File

class SafeMp3Editor {
    fun update(file: File, metadata: ScrapedMetadata, options: ScrapeOptions) {
        val source = Id3Codec.read(file)
        val edited = Id3TagEditor.mutateFrames(source.frames, metadata, options)
        if (source.version == Mp3TagVersion.V24 && Id3Codec.framesContentEquals(edited, source.frames)) return
        val frames = convertId3Frames(edited)

        AtomicAudioFileRewriter.replacePreservingRegion(
            source = file,
            sourceRegion = FileRegion(source.audioOffset, file.length() - source.audioOffset),
            writeTemporary = { temporary ->
                Id3Codec.writeTag(temporary, frames, file, source.audioOffset)
            },
            validateTemporary = { temporary ->
                val written = Id3Codec.read(temporary)
                require(written.version == Mp3TagVersion.V24 && Id3Codec.framesContentEquals(written.frames, frames)) {
                    "ID3 metadata validation failed"
                }
                FileRegion(written.audioOffset, temporary.length() - written.audioOffset)
            },
        )
    }
}

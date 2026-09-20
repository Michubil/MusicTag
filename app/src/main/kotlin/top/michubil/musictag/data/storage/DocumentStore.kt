package top.michubil.musictag.data.storage

import java.io.InputStream

/** Provider operations used by the tested SAF commit protocol. */
interface DocumentStore {
    fun children(directory: MusicDocument): List<MusicDocument>
    fun read(document: MusicDocument): InputStream
    fun create(directory: MusicDocument, name: String, mimeType: String): MusicDocument
    fun write(document: MusicDocument, source: InputStream)
    fun rename(document: MusicDocument, name: String): MusicDocument
    fun delete(document: MusicDocument)
}

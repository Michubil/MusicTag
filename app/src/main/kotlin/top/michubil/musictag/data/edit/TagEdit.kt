package top.michubil.musictag.data.edit

import top.michubil.musictag.data.model.*
import top.michubil.musictag.data.storage.MusicDocument

data class TagValues(val text: Map<MetadataField, String>, val hasCover: Boolean = false)
data class TagEditSource(val document: MusicDocument, val digest: String, val tags: TagValues)
data class TagEditInputs(val sources: List<TagEditSource>, val failures: List<String>)

data class TagDraft(
    val text: Map<MetadataField, String> = emptyMap(),
    val changed: Set<MetadataField> = emptySet(),
    val mixed: Set<MetadataField> = emptySet(),
    val cover: CoverImage? = null,
    val hasExistingCover: Boolean = false,
) {
    companion object {
        fun from(sources: List<TagEditSource>): TagDraft {
            val values = MetadataField.textFields.associateWith { field ->
                sources.map { it.tags.text[field].orEmpty() }.distinct()
            }
            return TagDraft(
                text = values.mapValues { (_, entries) -> entries.singleOrNull().orEmpty() },
                mixed = values.filterValues { it.size > 1 }.keys,
                hasExistingCover = sources.any { it.tags.hasCover },
            )
        }
    }

    fun mutation(mp3TagVersion: Mp3TagVersion = Mp3TagVersion.V24): TagMutation {
        require(changed.isNotEmpty()) { "请选择要修改的字段" }
        fun string(field: MetadataField): RemoteValue<String> = if (field !in changed) RemoteValue.Unavailable
            else text[field]?.trim()?.takeIf(String::isNotEmpty)?.let { RemoteValue.Available(it) } ?: RemoteValue.ConfirmedAbsent
        val metadata = ScrapedMetadata(
            title = string(MetadataField.TITLE),
            artists = string(MetadataField.ARTISTS).map(::parseArtistLines),
            album = string(MetadataField.ALBUM),
            date = string(MetadataField.DATE).map(ReleaseDate::parse),
            track = string(MetadataField.TRACK).map { TrackIndex.parse(it, "音轨号") },
            disc = string(MetadataField.DISC).map { TrackIndex.parse(it, "碟号") },
            lyrics = string(MetadataField.LYRICS),
            cover = if (MetadataField.COVER !in changed) RemoteValue.Unavailable
                else cover?.let { RemoteValue.Available(it) } ?: RemoteValue.ConfirmedAbsent,
        )
        return TagMutation(metadata, ScrapeOptions(MetadataField.entries.associateWith {
            FieldPolicy(enabled = it in changed, overwrite = true)
        }, formatLyricsTimeline = false, mp3TagVersion = mp3TagVersion))
    }
}

data class TagMutation(val metadata: ScrapedMetadata, val options: ScrapeOptions)

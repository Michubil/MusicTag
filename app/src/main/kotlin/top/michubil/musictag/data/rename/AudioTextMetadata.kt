package top.michubil.musictag.data.rename

data class AudioTextMetadata(
    val values: Map<RenameTag, String>,
    val date: String? = null,
    val artists: List<String> = emptyList(),
    val albumArtists: List<String> = emptyList(),
) {
    companion object {
        fun from(
            title: String? = null,
            artists: List<String> = emptyList(),
            album: String? = null,
            disc: String? = null,
            track: String? = null,
            date: String? = null,
            comment: String? = null,
            albumArtists: List<String> = emptyList(),
        ): AudioTextMetadata {
            fun index(value: String?, width: Int) = value?.substringBefore('/')?.trim()?.toIntOrNull()
                ?.takeIf { it > 0 }?.toString()?.padStart(width, '0')
            fun names(values: List<String>) = values.map(String::trim).filter(String::isNotEmpty).joinToString(" & ")
            val cleanedArtists = artists.map(String::trim).filter(String::isNotEmpty)
            val cleanedAlbumArtists = albumArtists.map(String::trim).filter(String::isNotEmpty)
            val year = date?.trim()?.let { Regex("^(\\d{4})(?:$|[-T])").find(it)?.groupValues?.get(1) }
                ?.takeUnless { it == "0000" }
            return AudioTextMetadata(
                mapOf(
                    RenameTag.TITLE to title, RenameTag.ARTISTS to names(cleanedArtists), RenameTag.ALBUM to album,
                    RenameTag.DISC to index(disc, 1), RenameTag.TRACK to index(track, 2), RenameTag.YEAR to year,
                    RenameTag.COMMENT to comment, RenameTag.ALBUM_ARTISTS to names(cleanedAlbumArtists),
                ).mapNotNull { (key, value) -> value?.trim()?.takeIf(String::isNotEmpty)?.let { key to it } }.toMap(),
                date = date?.trim()?.takeIf(String::isNotEmpty),
                artists = cleanedArtists,
                albumArtists = cleanedAlbumArtists,
            )
        }
    }
}

package top.michubil.musictag.data

import top.michubil.musictag.data.model.MusicSource
import top.michubil.musictag.data.model.SourceSelection
import top.michubil.musictag.data.model.defaultSourceSelections

/** Ordered `SOURCE=0/1` entries. Unknown sources are dropped; a malformed category is unusable. */
internal object SourceSelections {
    fun encode(selections: List<SourceSelection>): String = selections.joinToString(",") { selection ->
        selection.source.name + "=" + if (selection.enabled) "1" else "0"
    }

    fun decode(raw: String?): List<SourceSelection>? {
        if (raw.isNullOrBlank()) return null
        val seen = linkedSetOf<MusicSource>()
        val parsed = mutableListOf<SourceSelection>()
        for (part in raw.split(',')) {
            val bits = part.split('=')
            if (bits.size != 2) return null
            val source = MusicSource.entries.firstOrNull { it.name == bits[0] } ?: continue
            if (!seen.add(source)) continue
            val enabled = when (bits[1]) {
                "1" -> true
                "0" -> false
                else -> return null
            }
            parsed += SourceSelection(source, enabled)
        }
        if (parsed.isEmpty()) return null
        for (source in MusicSource.entries) {
            if (source !in seen) parsed += SourceSelection(source, enabled = false)
        }
        return parsed
    }

    fun migrate(orderName: String?): List<SourceSelection> = when (orderName) {
        null, "NETEASE_FIRST" -> defaultSourceSelections()
        "QQ_FIRST" -> listOf(
            SourceSelection(MusicSource.QQ, enabled = true),
            SourceSelection(MusicSource.NETEASE, enabled = true),
        )
        "NETEASE_ONLY" -> listOf(
            SourceSelection(MusicSource.NETEASE, enabled = true),
            SourceSelection(MusicSource.QQ, enabled = false),
        )
        "QQ_ONLY" -> listOf(
            SourceSelection(MusicSource.QQ, enabled = true),
            SourceSelection(MusicSource.NETEASE, enabled = false),
        )
        else -> defaultSourceSelections()
    }
}

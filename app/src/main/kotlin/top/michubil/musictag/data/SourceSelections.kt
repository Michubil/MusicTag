package top.michubil.musictag.data

import top.michubil.musictag.data.model.MusicSource
import top.michubil.musictag.data.model.SourceSelection
import top.michubil.musictag.data.model.defaultSourceSelections
import top.michubil.musictag.data.model.sourceSelections

/** Persist source switches in a stable order; previously saved order has no matching priority. */
internal object SourceSelections {
    fun encode(selections: List<SourceSelection>): String = MusicSource.entries.joinToString(",") { source ->
        source.name + "=" + if (selections.any { it.source == source && it.enabled }) "1" else "0"
    }

    fun decode(raw: String?): List<SourceSelection>? {
        if (raw.isNullOrBlank()) return null
        val seen = mutableSetOf<MusicSource>()
        val parsed = mutableMapOf<MusicSource, Boolean>()
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
            parsed[source] = enabled
        }
        if (parsed.isEmpty()) return null
        return MusicSource.entries.map { source -> SourceSelection(source, parsed[source] ?: false) }
    }

    fun migrate(orderName: String?): List<SourceSelection> = when (orderName) {
        "NETEASE_ONLY" -> sourceSelections(MusicSource.NETEASE)
        "QQ_ONLY" -> sourceSelections(MusicSource.QQ)
        else -> defaultSourceSelections()
    }
}

package top.michubil.musictag.data.lyrics

import kotlin.math.abs

data class LyricGroup(val startMs: Long?, val lines: List<String>)

object LyricsCodec {
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val millisecondTimestamp = Regex("\\[(\\d{1,3}):(\\d{2})[.:](\\d{2})\\d]")
    private val offset = Regex("^\\[offset:([+-]?\\d+)]$", RegexOption.IGNORE_CASE)
    private val metadata = Regex("^\\[(ar|al|ti|by|re|ve):.*]$", RegexOption.IGNORE_CASE)

    fun merge(original: String?, translation: String?): String? {
        val originals = parse(original.orEmpty())
        val translations = parse(translation.orEmpty()).toMutableList()
        if (originals.isEmpty()) return translation?.trim()?.takeIf(String::isNotEmpty)
        return originals.joinToString("\n") { group ->
            val translated = group.startMs?.let { time ->
                val exact = translations.indexOfFirst { it.startMs == time }
                val index = if (exact >= 0) exact else translations.indices
                    .filter { translations[it].startMs != null }
                    .minByOrNull { abs(requireNotNull(translations[it].startMs) - time) }
                    ?.takeIf { abs(requireNotNull(translations[it].startMs) - time) <= 200 } ?: -1
                if (index >= 0) translations.removeAt(index).lines else emptyList()
            }.orEmpty()
            val additions = translated.filter { line ->
                line.isNotBlank() && group.lines.none { it.trim() == line.trim() }
            }
            val prefix = group.startMs?.let(::formatTimestamp).orEmpty()
            (group.lines + additions).joinToString("\n") { "$prefix$it" }
        }.trim().takeIf(String::isNotEmpty)
    }

    fun formatTimeline(text: String): String = millisecondTimestamp.replace(text) { match ->
        "[" + match.groupValues[1] + ":" + match.groupValues[2] + "." + match.groupValues[3] + "]"
    }

    fun parse(text: String): List<LyricGroup> {
        if (text.isBlank()) return emptyList()
        val sourceLines = text.lineSequence().toList()
        val globalOffset = sourceLines.mapNotNull { line ->
            offset.matchEntire(line.trimEnd())?.groupValues?.get(1)?.toLong()
        }.lastOrNull() ?: 0L
        val timed = linkedMapOf<Long, MutableList<String>>()
        val untimed = mutableListOf<String>()
        sourceLines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (offset.matches(line)) return@forEach
            if (metadata.matches(line)) return@forEach
            val matches = timestamp.findAll(line).toList()
            val content = timestamp.replace(line, "").trim()
            if (matches.isEmpty()) {
                if (content.isNotBlank()) untimed += content
            } else {
                matches.forEach { match ->
                    val minute = match.groupValues[1].toLong()
                    val second = match.groupValues[2].toLong()
                    val fraction = match.groupValues[3]
                    val millis = when (fraction.length) {
                        0 -> 0L
                        1 -> fraction.toLong() * 100L
                        2 -> fraction.toLong() * 10L
                        else -> fraction.take(3).padEnd(3, '0').toLong()
                    }
                    val time = (minute * 60_000L + second * 1_000L + millis + globalOffset)
                        .coerceAtLeast(0L)
                    if (content.isNotBlank()) timed.getOrPut(time) { mutableListOf() } += content
                }
            }
        }
        return buildList {
            untimed.forEach { add(LyricGroup(null, listOf(it))) }
            timed.toSortedMap().forEach { (time, lines) -> add(LyricGroup(time, lines.toList())) }
        }
    }

    private fun formatTimestamp(timeMs: Long): String {
        val minutes = timeMs / 60_000L
        val seconds = timeMs % 60_000L / 1_000L
        val millis = timeMs % 1_000L
        return "[%02d:%02d.%03d]".format(minutes, seconds, millis)
    }
}

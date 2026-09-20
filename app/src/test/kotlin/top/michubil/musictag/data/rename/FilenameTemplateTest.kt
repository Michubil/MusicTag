package top.michubil.musictag.data.rename

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.storage.MusicDocument

class FilenameTemplateTest {
    private val tags = AudioTextMetadata.from("歌曲", listOf("甲", "乙"), "专辑", "2/3", "7/12", "2026-09-05", "注释", listOf("丙"))

    @Test
    fun fullDateIsPreservedWhileFilenameUsesYear() {
        assertEquals("2026-09-05", tags.date)
        assertEquals("2026.flac", FilenameTemplate("@6").filename("old.flac", tags))
    }

    @Test
    fun allReferencePresetsKeepExtensionAndFormatNumbers() {
        val expected = listOf("歌曲", "甲 & 乙 - 歌曲", "歌曲-甲 & 乙", "07. 歌曲", "07. 甲 & 乙 - 歌曲", "207. 歌曲", "207. 甲 & 乙 - 歌曲")
        RenamePreset.entries.filter { it != RenamePreset.CUSTOM }.zip(expected).forEach { (preset, stem) ->
            assertEquals("$stem.FLAC", FilenameTemplate(preset.pattern).filename("原名.FLAC", tags))
        }
        assertEquals("专辑_2026_注释_丙.wav", FilenameTemplate("@3_@6_@7_@8").filename("old.wav", tags))
    }

    @Test
    fun substitutionsAreOnePassAndEscapeLiteralAtSigns() {
        val value = AudioTextMetadata.from(title = "@2现场")
        assertEquals("@ @2现场.mp3", FilenameTemplate("@@ @1").filename("old.mp3", value))
    }

    @Test
    fun absentTagsAndInvalidNumbersAreNotInferredFromFilename() {
        val missing = AudioTextMetadata.from(title = "歌曲", disc = "0", track = "abc", date = "unknown")
        listOf("@2 - @1", "@4@5. @1", "@6 @1", "@7", "@8").forEach {
            assertThrows(IllegalArgumentException::class.java) { FilenameTemplate(it).filename("artist - song.mp3", missing) }
        }
        assertThrows(IllegalArgumentException::class.java) { FilenameTemplate("@1").filename("song.mp3", AudioTextMetadata.from()) }
    }

    @Test
    fun invalidTemplatesAreRejectedBeforeReadingFiles() {
        listOf("", "plain", "@@", "@", "@0", "@9", "@x", "@1".repeat(121)).forEach {
            assertThrows(RuntimeException::class.java) { FilenameTemplate(it) }
        }
    }

    @Test
    fun unsafeCharactersNormalizeWithoutCreatingDirectories() {
        val value = AudioTextMetadata.from(title = "  e\u0301:/\\*?\"<>|\nX... ")
        assertEquals("é__________X.mp3", FilenameTemplate("@1").filename("old.mp3", value))
        listOf("..", ".musictag-original-1", ".MusicTag-new-1", "中".repeat(85)).forEach {
            assertThrows(IllegalArgumentException::class.java) { FilenameTemplate("@1").filename("old.mp3", AudioTextMetadata.from(title = it)) }
        }
    }

    @Test
    fun conflictsIncludeHiddenAndCaseEquivalentSiblings() {
        val original = document("1", "old.mp3")
        val occupied = document("2", "SONG.MP3")
        val input = RenameInputs(listOf(RenameSource(original, AudioTextMetadata.from(title = "song"))), mapOf("parent" to listOf(original, occupied)))
        assertEquals("同目录已有此文件名", planRenames(input, FilenameTemplate("@1")).single().error)
        assertFalse(planRenames(input.copy(siblings = emptyMap()), FilenameTemplate("@1")).single().willRename)
    }

    @Test
    fun duplicateTargetsAllSkipButSeparateFoldersCanUseSameName() {
        val a = document("1", "a.mp3")
        val b = document("2", "b.mp3")
        val sources = listOf(a, b).map { RenameSource(it, AudioTextMetadata.from(title = "same")) }
        val input = RenameInputs(sources, mapOf("parent" to listOf(a, b)))
        val duplicate = planRenames(input, FilenameTemplate("@1"))
        assertTrue(duplicate.all { it.error == "多个所选文件生成了相同名称" })
        val other = b.copy(parentUri = "other")
        val separate = input.copy(sources = listOf(sources[0], sources[1].copy(document = other)), siblings = mapOf("parent" to listOf(a), "other" to listOf(other)))
        assertTrue(planRenames(separate, FilenameTemplate("@1")).all { it.willRename })
    }

    @Test
    fun unchangedNamesAreNoOpsAndErrorsDoNotBlockOtherFiles() {
        val a = document("1", "歌曲.mp3")
        val b = document("2", "b.mp3")
        val c = document("3", "c.flac")
        val input = RenameInputs(listOf(RenameSource(a, tags), RenameSource(b, error = "bad tag"), RenameSource(c, tags)), mapOf("parent" to listOf(a, b, c)))
        val entries = planRenames(input, FilenameTemplate("@1"))
        assertNull(entries[0].error)
        assertFalse(entries[0].willRename)
        assertEquals("bad tag", entries[1].error)
        assertTrue(entries[2].willRename)
    }

    private fun document(id: String, name: String) = MusicDocument("tree", id, "parent", name, canRename = true)
}

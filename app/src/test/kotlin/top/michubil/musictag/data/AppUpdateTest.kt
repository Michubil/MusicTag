package top.michubil.musictag.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppUpdateTest {
    @Test
    fun newerNumericVersionsAreUpdates() {
        assertTrue(AppUpdate.isNewerThan("1.0.1", "1.0.0"))
        assertTrue(AppUpdate.isNewerThan("v1.10.0", "1.9.0"))
        assertTrue(AppUpdate.isNewerThan("2.0", "1.9.9"))
    }

    @Test
    fun equalAndOlderVersionsAreNotUpdates() {
        assertFalse(AppUpdate.isNewerThan("1.0.0", "1.0.0"))
        assertFalse(AppUpdate.isNewerThan("v1.0.0", "1.0.0"))
        assertFalse(AppUpdate.isNewerThan("1.0", "1.0.0"))
        assertFalse(AppUpdate.isNewerThan("1.0.0", "1.0.1"))
        assertFalse(AppUpdate.isNewerThan("1.9.0", "1.10.0"))
    }

    @Test
    fun prefersTheMusicTagApkOnTheRelease() {
        val release = AppUpdate.releaseFrom(
            "v1.2.0",
            listOf(
                GitHubAsset("notes.txt", "https://github.com/Michubil/MusicTag/releases/download/v1.2.0/notes.txt"),
                GitHubAsset("other.apk", "https://github.com/Michubil/MusicTag/releases/download/v1.2.0/other.apk"),
                GitHubAsset("MusicTag-v1.2.0.apk", "https://github.com/Michubil/MusicTag/releases/download/v1.2.0/MusicTag-v1.2.0.apk"),
            ),
        )
        assertEquals("1.2.0", release.versionName)
        assertEquals("https://github.com/Michubil/MusicTag/releases/download/v1.2.0/MusicTag-v1.2.0.apk", release.downloadUrl)
    }

    @Test
    fun constructsTheReleaseApkUrlWhenNoTrustedAssetExists() {
        val release = AppUpdate.releaseFrom(
            "v1.2.0",
            listOf(
                GitHubAsset("MusicTag-v1.2.0.apk", "http://github.com/Michubil/MusicTag/releases/download/v1.2.0/MusicTag-v1.2.0.apk"),
                GitHubAsset("MusicTag-v1.2.0.apk", "https://example.com/MusicTag-v1.2.0.apk"),
            ),
        )
        assertEquals(AppUpdate.downloadUrl("v1.2.0", "1.2.0"), release.downloadUrl)
    }

    @Test
    fun rejectsInvalidReleaseTags() {
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.releaseFrom("", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.releaseFrom("v1.0.0/extra", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.releaseFrom("v", emptyList()) }
    }
}

package top.michubil.musictag.ui

import android.graphics.Bitmap
import top.michubil.musictag.data.edit.TagDraft
import top.michubil.musictag.data.edit.TagEditSource
import top.michubil.musictag.data.edit.TagMutation
import top.michubil.musictag.data.model.MetadataField

data class TagEditorState(
    val draft: TagDraft = TagDraft(),
    val fileName: String? = null,
    val batch: Boolean = false,
    val originalArtwork: Bitmap? = null,
    val selectedArtwork: Bitmap? = null,
    val loading: Boolean = false,
    val coverLoading: Boolean = false,
    val count: Int = 0,
    val failures: List<String> = emptyList(),
    val error: String? = null,
    val validation: String? = null,
) {
    val canChange: Boolean get() = !loading && !coverLoading
    val canSave: Boolean get() = canChange && count > 0 && error == null && validation == null && draft.changed.isNotEmpty()
    val artwork: Bitmap? get() = if (MetadataField.COVER in draft.changed) {
        if (draft.cover == null) null else selectedArtwork
    } else originalArtwork
}

/** Captured before navigation clears the editor; the application owns execution. */
internal data class TagSaveRequest(val sources: List<TagEditSource>, val mutation: TagMutation, val skipped: Int)

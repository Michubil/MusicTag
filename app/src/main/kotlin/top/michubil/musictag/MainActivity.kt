package top.michubil.musictag

import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import top.michubil.musictag.ui.MainAction
import top.michubil.musictag.ui.MainViewModel
import top.michubil.musictag.ui.navigation.MusicTagApp

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[MainViewModel::class.java] }
    private val coverPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.onAction(MainAction.TagCoverSelected(it.toString())) }
    }
    private val directoryPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            data?.data?.let { uri -> model.onAction(MainAction.StorageTreeSelected(uri.toString(), data.flags)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MusicTagApp(
                model,
                onChooseStorageTree = ::chooseStorageTree,
                onChooseTagCover = { coverPicker.launch(arrayOf("image/jpeg", "image/png")) },
                onOpenUrl = ::openUrl,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        model.onAction(MainAction.Refresh)
    }

    private fun openUrl(url: String) {
        val opened = runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }.isSuccess
        if (!opened) model.onAction(MainAction.ShowMessage("无法打开链接"))
    }

    private fun chooseStorageTree() {
        val previous = model.state.value.treeUri
        val initial = if (previous != null) previous.toUri() else
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music")
        directoryPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        })
    }
}

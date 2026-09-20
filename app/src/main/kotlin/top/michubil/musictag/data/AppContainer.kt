package top.michubil.musictag.data

import android.content.Context
import top.michubil.musictag.data.flac.SafeFlacEditor
import top.michubil.musictag.data.id3.SafeMp3Editor
import top.michubil.musictag.data.network.NetEaseClient
import top.michubil.musictag.data.network.QqMusicClient
import top.michubil.musictag.data.network.MetadataSourcesClient
import top.michubil.musictag.data.wav.SafeWavEditor

class AppContainer(context: Context) {
    val preferences = AppPreferences(context)
    val repository = MusicRepository(
        context,
        MetadataSourcesClient(NetEaseClient(), QqMusicClient()),
        SafeFlacEditor(),
        SafeMp3Editor(),
        SafeWavEditor(),
    )
}

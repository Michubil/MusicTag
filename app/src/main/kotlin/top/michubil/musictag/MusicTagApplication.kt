package top.michubil.musictag

import android.app.Application
import top.michubil.musictag.data.AppContainer

class MusicTagApplication : Application() {
    val container by lazy { AppContainer(this) }
}

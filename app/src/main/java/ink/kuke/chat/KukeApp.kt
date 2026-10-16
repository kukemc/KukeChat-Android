package ink.kuke.chat

import android.app.Application
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.service.Notifier

class KukeApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifier.ensureChannels(this)
    }
}

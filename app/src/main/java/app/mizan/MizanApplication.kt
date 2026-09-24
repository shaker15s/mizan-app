package app.mizan

import android.app.Application
import app.mizan.graph.AppGraph
import app.mizan.log.StartupTrace

class MizanApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Touch the trace so cold start has a defined origin.
        StartupTrace.startedAtElapsed
    }
}

package com.nethra.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.nethra.app.ai.Dictation
import com.nethra.app.ai.OpenRouterClient
import com.nethra.app.ai.PublishKitGenerator
import com.nethra.app.ai.ScriptGenerator
import com.nethra.app.ai.Transcriber
import com.nethra.app.ai.local.LocalGemma
import com.nethra.app.ai.local.ModelLocator
import com.nethra.app.captions.CaptionMaker
import com.nethra.app.core.AppPrefs
import com.nethra.app.core.NetworkMonitor
import com.nethra.app.media.MediaStoreVideos
import com.nethra.app.media.RecordingPostProcessor
import com.nethra.app.persist.DraftStore

/** Hand-wired dependencies. Swap implementations here (the extension point for new models/backends). */
class AppContainer(app: Application) {
    val network = NetworkMonitor(app)
    val openRouter = OpenRouterClient(network)
    val modelLocator = ModelLocator(app)
    val gemma = LocalGemma(app, modelLocator)
    val scripts = ScriptGenerator(openRouter)
    val transcriber = Transcriber(app, openRouter, gemma, network)
    val dictation = Dictation(openRouter)
    val publishKits = PublishKitGenerator(openRouter, gemma)
    val videos = MediaStoreVideos(app)
    val prefs = AppPrefs(app)
    val captions = CaptionMaker(app, openRouter, videos, gemma, network)
    val postProcessor = RecordingPostProcessor(app, videos, captions, prefs, network)
    val drafts = DraftStore(app)
}

class NethraApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.gemma.refresh()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Gemma holds GBs of RAM; drop it when Android asks and reload on next use.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) container.gemma.release()
    }
}

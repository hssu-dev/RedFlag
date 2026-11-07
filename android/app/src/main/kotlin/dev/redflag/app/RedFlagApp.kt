package dev.redflag.app

import android.app.Application
import android.content.Context

class RedFlagApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // The model dir must be created by the APP (owner = app uid). If `adb shell mkdir` creates it,
        // it is owned by shell with mode drwxrws--- and the app cannot read the pushed model
        // (seen on the S26 Ultra: "model missing" although the file was there).
        runCatching { dev.redflag.app.lite.LiteRtGemmaEngine.modelDir(this).mkdirs() }
    }
}

val Context.graph: AppGraph get() = (applicationContext as RedFlagApp).graph

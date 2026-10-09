package com.a3dmodelscreening

import android.app.Application
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import kotlin.concurrent.thread

/**
 * Preloads Filament's native libraries on a background thread as soon as the process starts,
 * overlapping that cost with system/Activity startup instead of paying it on the main thread
 * the first time MainScreen composes. Gltfio.init()/Filament.init()/Utils.init() only load
 * native .so files and run static setup - they are idempotent and thread-safe to call again
 * (MainScreen's rememberEngineInit still calls them before Engine.create(), which is what
 * needs to stay on the main thread for its EGL context).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        thread(name = "filament-preload") {
            runCatching {
                Gltfio.init()
                Filament.init()
                Utils.init()
            }
        }
    }
}

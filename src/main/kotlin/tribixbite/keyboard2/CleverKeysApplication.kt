package tribixbite.keyboard2

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import android.util.Log

/**
 * Application class for CleverKeys.
 *
 * THEORY #4: Explicit MultiDex initialization may be required even though
 * multiDexEnabled=true in build.gradle. InputMethodService binding might
 * fail if secondary dex files aren't loaded before service instantiation.
 */
class CleverKeysApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("CleverKeys", "✅ Application.onCreate() - MultiDex initialized")
    }

    /**
     * Install MultiDex support before any code is executed.
     * This ensures all classes are available when InputMethodService binds.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
        Log.d("CleverKeys", "✅ MultiDex.install() completed in attachBaseContext")
    }
}

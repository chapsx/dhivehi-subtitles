package mv.dhivehi.subtitles

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs(this).applyTheme()
    }
}

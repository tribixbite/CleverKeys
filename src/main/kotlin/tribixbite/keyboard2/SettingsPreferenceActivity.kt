package tribixbite.keyboard2

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Activity that hosts the PreferenceFragment for traditional Android settings UI.
 *
 * This provides full access to all settings defined in res/xml/settings.xml,
 * matching the original Unexpected-Keyboard settings experience.
 */
class SettingsPreferenceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_preference)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsPreferenceFragment())
                .commit()
        }

        // Enable back navigation
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

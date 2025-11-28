package tribixbite.keyboard2

import android.annotation.TargetApi
import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView

class LauncherActivity : Activity(), Handler.Callback {
    /** Text is replaced when receiving key events. */
    private lateinit var tryhereText: TextView
    private lateinit var tryhereArea: EditText
    /** Periodically restart the animations. */
    private lateinit var animations: MutableList<Animatable>
    private lateinit var handler: Handler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.launcher_activity)
        tryhereText = findViewById(R.id.launcher_tryhere_text)
        tryhereArea = findViewById(R.id.launcher_tryhere_area)
        if (Build.VERSION.SDK_INT >= 28) {
            tryhereArea.addOnUnhandledKeyEventListener(TryhereOnUnhandledKeyEventListener())
        }
        handler = Handler(Looper.getMainLooper(), this)
    }

    override fun onStart() {
        super.onStart()
        animations = mutableListOf()
        animations.add(findAnim(R.id.launcher_anim_swipe))
        animations.add(findAnim(R.id.launcher_anim_round_trip))
        animations.add(findAnim(R.id.launcher_anim_circle))
        handler.removeMessages(0)
        handler.sendEmptyMessageDelayed(0, 500)
    }

    override fun handleMessage(msg: Message): Boolean {
        for (anim in animations) {
            anim.start()
        }
        handler.sendEmptyMessageDelayed(0, 3000)
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.launcher_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.btnLaunchSettingsActivity) {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
        return super.onOptionsItemSelected(item)
    }

    fun launch_imesettings(btn: View) {
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    fun launch_imepicker(v: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    private fun findAnim(id: Int): Animatable {
        val img = findViewById<ImageView>(id)
        return img.drawable as Animatable
    }

    @TargetApi(28)
    inner class TryhereOnUnhandledKeyEventListener : View.OnUnhandledKeyEventListener {
        override fun onUnhandledKeyEvent(v: View, ev: KeyEvent): Boolean {
            // Don't handle the back key
            if (ev.keyCode == KeyEvent.KEYCODE_BACK) {
                return false
            }
            // Key release of modifiers would erase interesting data
            if (KeyEvent.isModifierKey(ev.keyCode)) {
                return false
            }
            val s = buildString {
                if (ev.isAltPressed) append("Alt+")
                if (ev.isShiftPressed) append("Shift+")
                if (ev.isCtrlPressed) append("Ctrl+")
                if (ev.isMetaPressed) append("Meta+")
                val kc = KeyEvent.keyCodeToString(ev.keyCode)
                append(kc.replaceFirst(Regex("^KEYCODE_"), ""))
            }
            tryhereText.text = s
            return false
        }
    }
}

package tribixbite.cleverkeys.processtext;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Test-only "foreign app" editor for ProcessTextResultInstrumentedTest.
 *
 * Declared in the androidTest manifest, so it belongs to the TEST APK's package and runs in that
 * package's own process — to CleverKeys it is an ordinary third-party editor, exactly like the
 * apps the textAssist / replaceText result path is written for (the service's onStartInput hook
 * skips CleverKeys' own package, so an editor inside the app process would not exercise it).
 *
 * Written in Java against framework APIs only: the test APK omits every library the tested app
 * already ships (kotlin-stdlib included), and this process does not load the app's classes, so a
 * Kotlin activity here would fail on its first stdlib call.
 *
 * The instrumentation process cannot touch these views directly, so the editor publishes its
 * state in a TextView ("pt-status": {@code sel=<start>,<end>|<text>}) that the test reads with
 * UiAutomator; {@link ProcessTextUppercaseActivity} (same process) edits the field through
 * {@link #current} to simulate the text changing while the processing app is open.
 */
public class ProcessTextHostActivity extends Activity {

    public static final String EXTRA_TEXT = "pt.text";
    public static final String EXTRA_SEL_START = "pt.selStart";
    public static final String EXTRA_SEL_END = "pt.selEnd";
    public static final String DESC_EDITOR = "pt-editor";
    public static final String DESC_STATUS = "pt-status";

    /** The live instance, for the processor activity in this same process. Main thread only. */
    static ProcessTextHostActivity current;

    EditText editor;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setContentDescription(DESC_STATUS);
        editor = new EditText(this) {
            @Override
            protected void onSelectionChanged(int selStart, int selEnd) {
                super.onSelectionChanged(selStart, selEnd);
                publish();
            }
        };
        editor.setContentDescription(DESC_EDITOR);
        editor.setSingleLine(false);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { publish(); }
        });
        root.addView(status);
        root.addView(editor);
        setContentView(root);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

        String text = getIntent().getStringExtra(EXTRA_TEXT);
        editor.setText(text == null ? "" : text);
        int length = editor.getText().length();
        int start = Math.min(getIntent().getIntExtra(EXTRA_SEL_START, length), length);
        int end = Math.min(getIntent().getIntExtra(EXTRA_SEL_END, length), length);
        editor.setSelection(start, end);
        editor.requestFocus();
        current = this;
        publish();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    /** Simulates the user (or the app) changing the field while the processing app is open. */
    void appendWhileAway(String suffix) {
        editor.getText().append(suffix);
    }

    private void publish() {
        if (status == null || editor == null) return;
        status.setText("sel=" + editor.getSelectionStart() + "," + editor.getSelectionEnd() + "|" + editor.getText());
    }
}

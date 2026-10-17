package tribixbite.cleverkeys.processtext;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Test-only ACTION_PROCESS_TEXT handler (androidTest manifest, test APK process): the
 * "text-processing app" the maintainer's phones lacked. It shows what it received and waits for
 * the test to press one of two buttons:
 *
 * <ul>
 *   <li>{@link #BUTTON_RETURN}: returns EXTRA_PROCESS_TEXT = the input upper-cased (RESULT_OK), or
 *       RESULT_CANCELED with no text when the caller sent EXTRA_PROCESS_TEXT_READONLY = true —
 *       the contract a real rewriting/translating app follows.</li>
 *   <li>{@link #BUTTON_EDIT_THEN_RETURN}: first appends {@link #EDIT_SUFFIX} to the host editor
 *       ({@link ProcessTextHostActivity}, same process) — the field changes while this app is
 *       open — then returns exactly as above.</li>
 * </ul>
 *
 * Java, framework APIs only, for the reason given on {@link ProcessTextHostActivity}.
 */
public class ProcessTextUppercaseActivity extends Activity {

    public static final String BUTTON_RETURN = "PT_RETURN";
    public static final String BUTTON_EDIT_THEN_RETURN = "PT_EDIT_THEN_RETURN";
    public static final String EDIT_SUFFIX = " edited";
    /** The received request, shown as {@code PT_INPUT:<text>|readonly=<flag>}. */
    public static final String INPUT_PREFIX = "PT_INPUT:";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final CharSequence input = getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        final boolean readOnly = getIntent().getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView received = new TextView(this);
        received.setText(INPUT_PREFIX + input + "|readonly=" + readOnly);
        Button ret = new Button(this);
        ret.setText(BUTTON_RETURN);
        ret.setOnClickListener(v -> finishWith(input, readOnly));
        Button editThenReturn = new Button(this);
        editThenReturn.setText(BUTTON_EDIT_THEN_RETURN);
        editThenReturn.setOnClickListener(v -> {
            ProcessTextHostActivity host = ProcessTextHostActivity.current;
            if (host != null) host.appendWhileAway(EDIT_SUFFIX);
            finishWith(input, readOnly);
        });
        root.addView(received);
        root.addView(ret);
        root.addView(editThenReturn);
        setContentView(root);
    }

    private void finishWith(CharSequence input, boolean readOnly) {
        if (input == null || readOnly) {
            setResult(RESULT_CANCELED);
        } else {
            Intent result = new Intent();
            result.putExtra(Intent.EXTRA_PROCESS_TEXT, input.toString().toUpperCase(Locale.ROOT));
            setResult(RESULT_OK, result);
        }
        finish();
    }
}

package tribixbite.cleverkeys

import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import java.util.Locale

/**
 * Builds the committed text carrying a [SuggestionSpanPolicy.Plan] as a SuggestionSpan.
 *
 * Flags 0 (the AOSP LatinIME commit pattern): no underline and no tap popup on every swiped
 * word; the alternatives appear in the editor's Replace popup (selection toolbar "Replace",
 * or the replaceTextMenu command). Honoured by stock EditText; Chrome/WebView and Compose may
 * ignore spans on committed text, which is harmless — the plain text is identical.
 * Any failure returns [text] unchanged: a span must never cost the word itself.
 */
internal object SuggestionSpanCommit {

    fun decorate(text: String, plan: SuggestionSpanPolicy.Plan, locale: Locale): CharSequence = runCatching {
        SpannableString(text).apply {
            setSpan(
                SuggestionSpan(locale, plan.suggestions.toTypedArray(), 0),
                plan.start, plan.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }.getOrDefault(text)
}

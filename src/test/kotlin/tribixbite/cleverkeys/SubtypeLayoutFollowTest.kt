package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Resources
import android.util.Log
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * gh #160 — "switching languages doesn't work": switching to a different CleverKeys
 * keyboard language (IME subtype) kept the first layout.
 *
 * Two independent breaks, both pinned here:
 *
 * 1. **The layouts pref shadows the subtype layout** (`LayoutManager.current_layout_unmodified`,
 *    LayoutManager.kt:72-87). A subtype change only calls `setLocaleTextLayout()`
 *    (KeyboardComponentGraph.refreshSubtypeAndLayout → LayoutManager.kt:56), but the resolved
 *    layout is `config.layouts[config.get_current_layout()]` whenever that entry is a NAMED
 *    layout — `localeTextLayout` is only reached through a `SystemLayout` (null) entry. A user
 *    who added language layouts in-app (which #160's author did: "I'm able to add multiple
 *    language layouts") therefore sees NOTHING happen on an OS-level language switch.
 *
 * 2. **languageTag aliasing in SubtypeManager.defaultSubtypes** (SubtypeManager.kt:100-107).
 *    The current subtype is mapped back into the enabled list by `languageTag` alone, and
 *    method.xml declares duplicate tags (ar/ar_TN both "ar", en/en_NG both "en"). Switching
 *    between two same-tag subtypes always resolves the FIRST one — its `default_layout` wins
 *    and the switch is invisible.
 *
 * Harness: mock tier (runMockTests). `LayoutManager`'s constructor builds an
 * `android.util.LruCache` (a throwing android.jar stub) and `SubtypeManager`'s does a
 * `getSystemService` lookup, so both are Objenesis-allocated with only the fields these
 * paths read seeded — same pattern as [SuggestionTapAddAndIWordTest].
 */
class SubtypeLayoutFollowTest {

    private val objenesis = ObjenesisStd()

    private lateinit var config: Config
    private var currentLayoutIndex = 0

    /** A named layout as loaded from the layouts pref / a subtype's default_layout. */
    private fun layout(name: String?): KeyboardData =
        mockk<KeyboardData>(relaxed = true) { every { this@mockk.name } returns name }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        currentLayoutIndex = 0
        config = mockk(relaxed = true)
        every { config.get_current_layout() } answers { currentLayoutIndex }
        every { config.set_current_layout(any()) } answers { currentLayoutIndex = firstArg() }
    }

    @After
    fun teardown() = unmockkAll()

    private fun layoutManager(locale: KeyboardData, layouts: List<KeyboardData?>): LayoutManager {
        config.layouts = layouts
        val lm = objenesis.newInstance(LayoutManager::class.java)
        lm.setField("config", config)
        lm.setField("localeTextLayout", locale)
        return lm
    }

    // ------------------------------------------------------- break 1: pref shadows subtype

    @Test
    fun subtypeSwitchUpdatesTheResolvedLayoutWhenThePrefPinsNamedLayouts() {
        val qwertyUs = layout("QWERTY (US)")
        val jcukenRu = layout("ЙЦУКЕН (Русский)")
        val lm = layoutManager(locale = qwertyUs, layouts = listOf(qwertyUs, jcukenRu))

        // What KeyboardComponentGraph.refreshSubtypeAndLayout does on
        // onCurrentInputMethodSubtypeChanged: hand the new subtype's default layout over.
        lm.setLocaleTextLayout(jcukenRu)

        assertWithMessage(
            "gh #160: after the ru subtype is selected the resolved layout must follow — " +
                "the layouts-pref entry pinned at the old index must not shadow it"
        ).that(lm.current_layout_unmodified()).isSameInstanceAs(jcukenRu)
    }

    @Test
    fun subtypeSwitchFallsBackToTheSystemEntryWhenNoNamedEntryMatches() {
        val qwertyUs = layout("QWERTY (US)")
        val jcukenRu = layout("ЙЦУКЕН (Русский)")
        // The user kept the default System entry but pinned QWERTY first.
        val lm = layoutManager(locale = qwertyUs, layouts = listOf(qwertyUs, null))

        lm.setLocaleTextLayout(jcukenRu)

        assertWithMessage(
            "no pref entry names the new subtype's layout, but a SystemLayout (null) entry " +
                "exists — selection must move there so the locale layout shows"
        ).that(lm.current_layout_unmodified()).isSameInstanceAs(jcukenRu)
    }

    @Test
    fun anUnchangedLocaleLayoutNeverYanksAUserSelectedLayout() {
        val qwertyUs = layout("QWERTY (US)")
        val jcukenRu = layout("ЙЦУКЕН (Русский)")
        val lm = layoutManager(locale = qwertyUs, layouts = listOf(qwertyUs, jcukenRu))
        currentLayoutIndex = 1 // the user picked ru IN-APP; the subtype is still en

        // A refresh that re-delivers the SAME locale layout (e.g. the graph's qwerty_us
        // fallback when resolution fails, or a spurious subtype-changed callback) must not
        // move the selection.
        lm.setLocaleTextLayout(layout("QWERTY (US)"))

        assertThat(lm.current_layout_unmodified()).isSameInstanceAs(jcukenRu)
        verify(exactly = 0) { config.set_current_layout(any()) }
    }

    @Test
    fun withNeitherAMatchingEntryNorASystemEntryThePrefSelectionWins() {
        val qwertyUs = layout("QWERTY (US)")
        val azerty = layout("AZERTY (Français)")
        val jcukenRu = layout("ЙЦУКЕН (Русский)")
        val lm = layoutManager(locale = qwertyUs, layouts = listOf(qwertyUs, azerty))

        lm.setLocaleTextLayout(jcukenRu)

        // Documented limitation: the user removed the System entry AND has no entry for the
        // new language — there is nowhere sane to point, so the explicit pref selection wins.
        assertThat(lm.current_layout_unmodified()).isSameInstanceAs(qwertyUs)
    }

    // ----------------------------------------------- break 2: languageTag aliasing

    private fun subtype(tag: String, defaultLayout: String): InputMethodSubtype =
        mockk {
            every { languageTag } returns tag
            every { getExtraValueOf("default_layout") } returns defaultLayout
            every { getExtraValueOf("extra_keys") } returns null
            every { getExtraValueOf("script") } returns null
        }

    private fun subtypeManager(
        enabled: List<InputMethodSubtype>,
        current: InputMethodSubtype?
    ): SubtypeManager {
        val imi = mockk<InputMethodInfo> { every { packageName } returns "tribixbite.cleverkeys" }
        val imm = mockk<InputMethodManager> {
            every { enabledInputMethodList } returns listOf(imi)
            every { getEnabledInputMethodSubtypeList(imi, true) } returns enabled
            every { currentInputMethodSubtype } returns current
        }
        val context = mockk<Context> { every { packageName } returns "tribixbite.cleverkeys" }
        val sm = objenesis.newInstance(SubtypeManager::class.java)
        sm.setField("context", context)
        sm.setField("inputMethodManager", imm)
        return sm
    }

    @Test
    fun switchingBetweenTwoSubtypesSharingALanguageTagResolvesTheSelectedOne() {
        // NOTE: driven through the extracted pure seam, not refreshSubtype — the aliasing
        // lives in defaultSubtypes' API-24+ branch, and Build.VERSION.SDK_INT is 0 under
        // this harness (android.jar stub), which routes the <24 branch instead. The seam IS
        // what that branch now calls.
        val ar = subtype("ar", "arab_pc_hindu")
        val arTn = subtype("ar", "arab_pc")

        val selected = SubtypeManager.selectCurrentSubtype(listOf(ar, arTn), arTn)

        assertWithMessage(
            "gh #160: ar and ar_TN share languageTag \"ar\" — a tag-only match always " +
                "resolves the FIRST subtype, so switching between them never changes the layout"
        ).that(selected).isSameInstanceAs(arTn)
    }

    @Test
    fun aRandomSameLanguageAnswerStillMapsToTheEnabledSubtype() {
        // The languageTag fallback the API-24 gate exists for: the system reports a subtype
        // instance that is not (equal to) any enabled one — tag matching still finds ours.
        val en = subtype("en", "latn_qwerty_us")
        val randomEn = subtype("en", "latn_qwerty_gb")

        assertThat(SubtypeManager.selectCurrentSubtype(listOf(en), randomEn))
            .isSameInstanceAs(en)
        assertThat(SubtypeManager.selectCurrentSubtype(listOf(en), subtype("ru", "x")))
            .isNull()
    }

    @Test
    fun theSubtypeDeliveredByTheChangeCallbackWinsOverAStaleImmAnswer() {
        val en = subtype("en", "latn_qwerty_us")
        val ru = subtype("ru", "cyrl_jcuken_ru")
        val kdEn = layout("QWERTY (US)")
        val kdRu = layout("ЙЦУКЕН (Русский)")
        mockkObject(LayoutsPreference.Companion)
        every { LayoutsPreference.layoutOfString(any(), "latn_qwerty_us") } returns kdEn
        every { LayoutsPreference.layoutOfString(any(), "cyrl_jcuken_ru") } returns kdRu

        // onCurrentInputMethodSubtypeChanged carries the NEW subtype; the IMM query can
        // still answer with the OLD one inside the callback window. The delivered subtype
        // is authoritative.
        val sm = subtypeManager(enabled = listOf(en, ru), current = en)
        // RED captured 2026-09-03 with the parameterless call: refreshSubtype discarded the
        // callback's subtype and the stale IMM answer won (resolved QWERTY, expected ЙЦУКЕН).
        val resolved = sm.refreshSubtype(config, mockk<Resources>(), changedTo = ru)

        assertThat(resolved).isSameInstanceAs(kdRu)
    }

    // ------------------------------------------------------------------ reflection

    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage(
            "field '$name' not found on ${javaClass.simpleName} — it was renamed or removed; " +
                "declared: ${javaClass.declaredFields.map { it.name }}"
        ).that(field).isNotNull()
        field!!.isAccessible = true
        field.set(this, value)
    }
}

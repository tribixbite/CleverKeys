package tribixbite.cleverkeys

import android.os.Handler
import android.os.Message
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.lang.reflect.Field
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.Pointers.Companion.FLAG_P_FAKE
import tribixbite.cleverkeys.Pointers.Companion.FLAG_P_LATCHED
import tribixbite.cleverkeys.Pointers.Companion.FLAG_P_LOCKED
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.popover.PopoverSlot
import tribixbite.cleverkeys.popover.SubkeyPopoverMetrics
import tribixbite.cleverkeys.popover.SubkeyPopoverState
import tribixbite.cleverkeys.prefs.ConfigSnapshot

/**
 * A long press on a modifier locks it (caps lock for Shift), also when that modifier is already
 * latched. Maintainer device check 2026-11-23: at a sentence start, where auto-capitalisation
 * has latched Shift, a long press on Shift released it instead of locking it, so caps lock took
 * two long presses. The hold locked the pressing finger's pointer; its release then found the
 * latched Shift ([Pointers.onTouchUp] "already latched") and took both away. With "double tap
 * for caps lock" off, a Shift the user had tapped went the same way. The same day: a double tap
 * locks Ctrl, Alt, Fn and Meta as it locks Shift (it used to release them); then, holding Shift or
 * Ctrl opens their subkey popover instead of locking them (with the popover and the double-tap lock
 * on), and a quick double tap at a sentence start, where the first tap releases
 * auto-capitalisation's Shift, gives caps lock. Later that day: every lockable modifier holds that
 * way (one with nothing to show still locks), a double tap has to be quick (TalkBack and
 * accessibility clicks excepted), and a modifier held while another finger types applies to it.
 *
 * Driven through the real [Pointers.onTouchDown], [Pointers.handleMessage] with the pointer's
 * long-press `what` (as the Handler delivers it) and [Pointers.onTouchUp]. Harness as in
 * [PointersSubkeyPopoverTest]: [Pointers] allocated without its constructor, collaborators set.
 */
class ModifierLongPressLockTest {

    private val objenesis = ObjenesisStd()
    private lateinit var pointers: Pointers
    private lateinit var ptrs: ArrayList<Pointers.Pointer>
    private lateinit var handler: RecordingHandler
    private lateinit var config: Config

    private val shift = KeyValue.getKeyByName("shift")
    private val ctrl = KeyValue.getKeyByName("ctrl")
    private val shiftKey = KeyboardData.Key.EMPTY.withKeyValue(0, shift)
    private val ctrlKey = KeyboardData.Key.EMPTY.withKeyValue(0, ctrl)

    /** The shipped defaults that matter here: double tap locks Shift, no word swipe or short swipes. */
    private fun snap(
        doubleTapLock: Boolean = true,
        swipeTyping: Boolean = false,
        shortGestures: Boolean = false,
        popover: Boolean = false,
    ): ConfigSnapshot = testConfigSnapshot(
        double_tap_lock_shift = doubleTapLock,
        swipe_typing_enabled = swipeTyping,
        short_gestures_enabled = shortGestures,
        subkey_popover_enabled = popover,
        subkey_popover_neutral_width = 60,
        subkey_popover_neutral_height = 60,
    )

    /** The test's clock for the double-tap window ([Pointers.clockForTest]). */
    private var clockMs = 1_000L

    private fun useSnapshot(s: ConfigSnapshot) {
        every { config.snapshot } returns s
    }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        ptrs = ArrayList()
        handler = RecordingHandler()
        pointers = objenesis.newInstance(Pointers::class.java)
        config = mockk(relaxed = true)
        useSnapshot(snap())
        val recognizer = mockk<EnhancedSwipeGestureRecognizer>(relaxed = true)
        every { recognizer.isSwipeTyping() } returns false
        pointers.setField("_ptrs", ptrs)
        pointers.setField("_config", config)
        pointers.setField("_handler", handler)
        pointers.setField("_longpress_handler", mockk<Handler>(relaxed = true))
        pointers.setField("_swipeRecognizer", recognizer)
        pointers.setField("_gestureClassifier", GestureClassifier())
        val swipeManager = mockk<ShortSwipeCustomizationManager>()
        every { swipeManager.getMapping(any(), any()) } returns null
        pointers.setField("_customSwipeManager", swipeManager)
        // Absent before the double-tap window existed: the real clock then.
        if (Pointers::class.java.declaredFields.any { it.name == "clockForTest" }) {
            pointers.setField("clockForTest", { clockMs })
        }
    }

    @After
    fun tearDown() = unmockkAll()

    // ------------------------------------------------------------------ drivers

    private fun press(key: KeyboardData.Key) = pointers.onTouchDown(50f, 50f, FINGER, key)

    /** The long-press timer of the finger's pointer fires, as the Handler would deliver it. */
    private fun holdPastTheLongPress() {
        val pressing = ptrs.single { it.pointerId == FINGER }
        pointers.handleMessage(message(pressing.timeoutWhat))
    }

    private fun release() = pointers.onTouchUp(FINGER)

    private fun tap(key: KeyboardData.Key) {
        press(key)
        release()
    }

    private fun longPress(key: KeyboardData.Key) {
        press(key)
        holdPastTheLongPress()
        release()
    }

    /** Auto-capitalisation at a sentence start (Keyboard2View.set_shift_state(true, false)). */
    private fun autocapLatchesShift() = pointers.set_fake_pointer_state(shiftKey, shift, latched = true, lock = false)

    private fun pointersFor(kv: KeyValue) = ptrs.filter { it.value == kv }

    private fun assertLockedByTheUser(kv: KeyValue) {
        val held = pointersFor(kv)
        assertWithMessage("one $kv pointer, not a lock beside a latch: ${held.map { "0x" + it.flags.toString(16) }}")
            .that(held).hasSize(1)
        val p = held.single()
        assertWithMessage("$kv latched and locked after the release")
            .that(p.flags and (FLAG_P_LATCHED or FLAG_P_LOCKED)).isEqualTo(FLAG_P_LATCHED or FLAG_P_LOCKED)
        assertWithMessage("the lock is the user's: auto-capitalisation must not take it back")
            .that(p.flags and FLAG_P_FAKE).isEqualTo(0)
        assertThat(p.pointerId).isEqualTo(-1)
        assertThat(pointers.getModifiers().has(kv.getModifier())).isTrue()
    }

    // ------------------------------------------------------------------ the fix

    @Test
    fun holdingShiftOverAutoCapitalisationsShiftLocksIt() {
        autocapLatchesShift()
        longPress(shiftKey)
        assertLockedByTheUser(shift)
    }

    @Test
    fun theLockShowsWhileTheFingerIsStillDown() {
        autocapLatchesShift()
        press(shiftKey)
        holdPastTheLongPress()
        // Keyboard2View.isShiftLocked and the Shift key's drawing read the first Shift pointer.
        assertWithMessage("Shift drawn locked during the hold, not still auto-capitalisation's latch")
            .that(pointers.getKeyFlags(shift) and FLAG_P_LOCKED).isEqualTo(FLAG_P_LOCKED)
        assertThat(handler.haptics).contains(HapticEvent.LONG_PRESS)
        release()
        assertLockedByTheUser(shift)
    }

    @Test
    fun holdingAShiftTheUserTappedLocksItWithDoubleTapLockOff() {
        useSnapshot(snap(doubleTapLock = false))
        tap(shiftKey)
        assertThat(pointersFor(shift).single().flags and FLAG_P_LATCHED).isEqualTo(FLAG_P_LATCHED)
        longPress(shiftKey)
        assertLockedByTheUser(shift)
    }

    @Test
    fun holdingALatchedCtrlLocksIt() {
        tap(ctrlKey)
        longPress(ctrlKey)
        assertLockedByTheUser(ctrl)
    }

    @Test
    fun autoCapitalisationLeavesTheLockAlone() {
        autocapLatchesShift()
        longPress(shiftKey)
        // The next letters make auto-capitalisation drop its Shift, and a new sentence latch it.
        pointers.set_fake_pointer_state(shiftKey, shift, latched = false, lock = false)
        assertLockedByTheUser(shift)
        pointers.set_fake_pointer_state(shiftKey, shift, latched = true, lock = false)
        assertLockedByTheUser(shift)
    }

    @Test
    fun withWordSwipesAndShortSwipesOnTheHoldStillLocks() {
        useSnapshot(snap(swipeTyping = true, shortGestures = true))
        autocapLatchesShift()
        longPress(shiftKey)
        assertLockedByTheUser(shift)
    }

    // ------------------------------------------------------------------ double tap

    // Maintainer 2026-11-23: "what happened to ctrl lock (pressing control twice, similar to
    // pressing shift twice = capslock)?" Only Shift ever had the double-tap lock (as upstream);
    // a second tap on Ctrl released it. Every lockable modifier now follows the same setting.

    private fun keyFor(kv: KeyValue) = KeyboardData.Key.EMPTY.withKeyValue(0, kv)

    @Test
    fun doubleTappingCtrlLocksIt() {
        tap(ctrlKey)
        tap(ctrlKey)
        assertLockedByTheUser(ctrl)
    }

    @Test
    fun doubleTappingAltFnOrMetaLocksIt() {
        for (name in listOf("alt", "fn", "meta")) {
            ptrs.clear()
            val kv = KeyValue.getKeyByName(name)
            tap(keyFor(kv))
            tap(keyFor(kv))
            assertLockedByTheUser(kv)
        }
    }

    @Test
    fun aTapOnALockedCtrlReleasesIt() {
        tap(ctrlKey)
        tap(ctrlKey)
        tap(ctrlKey)
        assertThat(pointersFor(ctrl)).isEmpty()
        assertThat(pointers.getModifiers().has(KeyValue.Modifier.CTRL)).isFalse()
    }

    @Test
    fun withDoubleTapLockOffASecondTapReleasesCtrl() {
        useSnapshot(snap(doubleTapLock = false))
        tap(ctrlKey)
        tap(ctrlKey)
        assertThat(pointersFor(ctrl)).isEmpty()
    }

    // ------------------------------------------------------------------ hold opens the popover

    // Maintainer 2026-11-23: "make it so holding ctrl and shift activate the subkey popup instead".

    private val menu = KeyValue.getKeyByName("menu")
    private val shiftWithSubkeys = shiftKey.withKeyValue(SwipeDirection.SW.subLabelIndex, menu)
    private val ctrlWithSubkeys = ctrlKey.withKeyValue(SwipeDirection.N.subLabelIndex, KeyValue.getKeyByName("home"))

    private fun hold(key: KeyboardData.Key) {
        press(key)
        holdPastTheLongPress()
    }

    @Test
    fun holdingShiftOpensItsSubkeysInsteadOfLocking() {
        useSnapshot(snap(popover = true))
        hold(shiftWithSubkeys)
        val shown = handler.shown
        assertWithMessage("the hold opens Shift's subkey popover").that(shown).isNotNull()
        assertThat((shown!!.slot(SwipeDirection.SW) as PopoverSlot.Default).value).isEqualTo(menu)
        assertWithMessage("and does not lock it").that(pointers.getKeyFlags(shift) and FLAG_P_LOCKED).isEqualTo(0)
        release() // in the neutral zone: nothing
        assertThat(pointersFor(shift)).isEmpty()
        assertThat(pointers.getModifiers().has(KeyValue.Modifier.SHIFT)).isFalse()
    }

    @Test
    fun holdingCtrlOpensItsSubkeysInsteadOfLocking() {
        useSnapshot(snap(popover = true))
        hold(ctrlWithSubkeys)
        assertWithMessage("the hold opens Ctrl's subkey popover").that(handler.shown).isNotNull()
        release()
        assertThat(pointersFor(ctrl)).isEmpty()
    }

    @Test
    fun holdingShiftOverAutoCapitalisationsShiftLeavesItsLatch() {
        useSnapshot(snap(popover = true))
        autocapLatchesShift()
        hold(shiftWithSubkeys)
        assertThat(handler.shown).isNotNull()
        release()
        val held = pointersFor(shift).single()
        assertThat(held.flags and (FLAG_P_FAKE or FLAG_P_LATCHED or FLAG_P_LOCKED)).isEqualTo(FLAG_P_FAKE or FLAG_P_LATCHED)
    }

    @Test
    fun withTheDoubleTapLockOffTheHoldStillLocks() {
        // Locking must stay reachable: without the double tap, the hold is the way.
        useSnapshot(snap(popover = true, doubleTapLock = false))
        longPress(shiftWithSubkeys)
        assertThat(handler.shown).isNull()
        assertLockedByTheUser(shift)
    }

    @Test
    fun altKeepsItsLockingHold() {
        useSnapshot(snap(popover = true))
        val alt = KeyValue.getKeyByName("alt")
        longPress(keyFor(alt))
        assertThat(handler.shown).isNull()
        assertLockedByTheUser(alt)
    }

    @Test
    fun underTalkBackTheHoldStillLocks() {
        // The popover has no accessibility nodes (docs/specs/subkey-popover.md "Scope").
        useSnapshot(snap(popover = true))
        handler.touchExploration = true
        longPress(shiftWithSubkeys)
        assertThat(handler.shown).isNull()
        assertLockedByTheUser(shift)
    }

    // ------------------------------------------------------------------ double tap at a sentence start

    @Test
    fun aQuickDoubleTapOnAutoCapitalisationsShiftIsCapsLock() {
        autocapLatchesShift()
        tap(shiftKey) // releases auto-capitalisation's Shift
        clockMs += 150
        tap(shiftKey)
        assertLockedByTheUser(shift)
    }

    @Test
    fun aSlowSecondTapAfterReleasingAutoCapitalisationsShiftIsOneShot() {
        autocapLatchesShift()
        tap(shiftKey)
        clockMs += 1_500
        tap(shiftKey)
        val held = pointersFor(shift).single()
        assertThat(held.flags and (FLAG_P_LATCHED or FLAG_P_LOCKED)).isEqualTo(FLAG_P_LATCHED)
    }

    @Test
    fun aKeyTypedBetweenTheTapsMakesItOneShot() {
        autocapLatchesShift()
        tap(shiftKey)
        tap(KeyboardData.Key.EMPTY.withKeyValue(0, KeyValue.makeCharKey('a')))
        tap(shiftKey)
        val held = pointersFor(shift).single()
        assertThat(held.flags and (FLAG_P_LATCHED or FLAG_P_LOCKED)).isEqualTo(FLAG_P_LATCHED)
    }

    @Test
    fun withTheDoubleTapLockOffTwoTapsOnAutoCapitalisationsShiftGiveOneShot() {
        useSnapshot(snap(doubleTapLock = false))
        autocapLatchesShift()
        tap(shiftKey)
        tap(shiftKey)
        val held = pointersFor(shift).single()
        assertThat(held.flags and (FLAG_P_LATCHED or FLAG_P_LOCKED)).isEqualTo(FLAG_P_LATCHED)
    }

    // ------------------------------------------------------------------ one rule for every modifier

    // Maintainer 2026-11-23, of the Shift/Ctrl popover: "shouldnt Fn key follow suit?" Every
    // modifier a double tap locks now holds the same way: its subkeys when it has any to show;
    // one with nothing to show (an Alt or Meta key added to a layout) keeps the locking hold.

    private val fn = KeyValue.getKeyByName("fn")
    private val fnWithSubkeys = keyFor(fn).withKeyValue(SwipeDirection.SW.subLabelIndex, KeyValue.getKeyByName("config"))

    @Test
    fun holdingFnOpensItsSubkeys() {
        useSnapshot(snap(popover = true))
        hold(fnWithSubkeys)
        assertWithMessage("the hold opens Fn's subkey popover").that(handler.shown).isNotNull()
        assertThat(pointers.getKeyFlags(fn) and FLAG_P_LOCKED).isEqualTo(0)
        release()
        assertThat(pointersFor(fn)).isEmpty()
    }

    @Test
    fun holdingAltOrMetaWithSubkeysOpensThem() {
        useSnapshot(snap(popover = true))
        for (name in listOf("alt", "meta")) {
            ptrs.clear()
            handler.shown = null
            val kv = KeyValue.getKeyByName(name)
            hold(keyFor(kv).withKeyValue(SwipeDirection.N.subLabelIndex, KeyValue.getKeyByName("home")))
            assertWithMessage("the hold opens $name's subkey popover").that(handler.shown).isNotNull()
            release()
            assertThat(pointersFor(kv)).isEmpty()
        }
    }

    @Test
    fun aModifierWithNothingToShowKeepsItsLockingHold() {
        useSnapshot(snap(popover = true))
        for (kv in listOf(shift, ctrl, fn)) {
            ptrs.clear()
            handler.shown = null
            longPress(keyFor(kv))
            assertWithMessage("no empty popover for $kv").that(handler.shown).isNull()
            assertLockedByTheUser(kv)
        }
    }

    /**
     * Maintainer 2026-11-23: "dont overwrite the trackpoint style nav for holding the nav key
     * (between space and return)". That key (Compose, with the arrows and page keys as subkeys,
     * res/xml/bottom_row.xml) keeps TrackPoint with the popover on.
     */
    @Test
    fun holdingTheNavKeyStillStartsTrackPoint() {
        loadComposeData()
        useSnapshot(testConfigSnapshot(keyrepeat_enabled = true, double_tap_lock_shift = true, subkey_popover_enabled = true))
        var nav = keyFor(KeyValue.getKeyByName("compose"))
        for ((index, name) in listOf(7 to "up", 6 to "right", 5 to "left", 8 to "down", 1 to "doc_home", 3 to "doc_end")) {
            nav = nav.withKeyValue(index, KeyValue.getKeyByName(name))
        }
        hold(nav)
        assertWithMessage("no popover on the nav key").that(handler.shown).isNull()
        assertWithMessage("its hold is TrackPoint")
            .that(ptrs.single { it.pointerId == FINGER }.flags and Pointers.FLAG_P_TRACKPOINT_MODE)
            .isEqualTo(Pointers.FLAG_P_TRACKPOINT_MODE)
    }

    // ------------------------------------------------------------------ a double tap is quick

    // A second tap counts as a double tap only when it starts within DOUBLE_TAP_LOCK_MS of the
    // first tap's release, as in other keyboards; a slower one releases the modifier (it used to
    // lock it at any delay, so cancelling a mistaken Ctrl took a third tap). TalkBack, where
    // every activation is its own double tap, keeps the old rule.

    @Test
    fun aSlowSecondTapReleasesTheModifier() {
        for (kv in listOf(shift, ctrl, KeyValue.getKeyByName("alt"), fn, KeyValue.getKeyByName("meta"))) {
            ptrs.clear()
            tap(keyFor(kv))
            clockMs += 1_500
            tap(keyFor(kv))
            assertWithMessage("a slow second tap on $kv turns it off").that(pointersFor(kv)).isEmpty()
            assertThat(pointers.getModifiers().has(kv.getModifier())).isFalse()
        }
    }

    @Test
    fun theWindowRunsFromTheFirstReleaseToTheSecondPress() {
        tap(ctrlKey)
        clockMs += 350
        press(ctrlKey)
        clockMs += 300 // a slow finger: the press, not the release, has to be in time
        release()
        assertLockedByTheUser(ctrl)
    }

    @Test
    fun atASentenceStartTheWindowAlsoEndsAtTheSecondPress() {
        autocapLatchesShift()
        tap(shiftKey)
        clockMs += 350
        press(shiftKey)
        clockMs += 300
        release()
        assertLockedByTheUser(shift)
    }

    @Test
    fun underTalkBackASlowSecondTapStillLocks() {
        handler.touchExploration = true
        tap(ctrlKey)
        clockMs += 1_500
        tap(ctrlKey)
        assertLockedByTheUser(ctrl)
    }

    /** A key activated through accessibility (Keyboard2View.activateKeyForAccessibility). */
    private fun clickForAccessibility(key: KeyboardData.Key) {
        pointers.onTouchDown(50f, 50f, Pointers.ACCESSIBILITY_POINTER_ID, key)
        pointers.onTouchUp(Pointers.ACCESSIBILITY_POINTER_ID)
    }

    @Test
    fun anAccessibilityClickIsADoubleTapAtAnyDelay() {
        // Switch Access (no touch exploration) cannot activate a key twice within the window.
        clickForAccessibility(ctrlKey)
        clockMs += 1_500
        clickForAccessibility(ctrlKey)
        assertLockedByTheUser(ctrl)
    }

    // ------------------------------------------------------------------ a held modifier applies

    // Holding a modifier while another finger types applies it to what is typed, as on a hardware
    // keyboard (Ctrl+C, Shift+letter), and it ends with the finger. It used to skip the first key
    // (a pressed modifier only counted once latched), apply to later ones, and leave the hold to
    // lock the modifier or open its popover.

    private val letterA = keyFor(KeyValue.makeCharKey('a'))
    private val letterB = keyFor(KeyValue.makeCharKey('b'))

    private fun pressWith(finger: Int, key: KeyboardData.Key) = pointers.onTouchDown(50f, 50f, finger, key)
    private fun releaseFinger(finger: Int) = pointers.onTouchUp(finger)

    private fun tapWith(finger: Int, key: KeyboardData.Key) {
        pressWith(finger, key)
        releaseFinger(finger)
    }

    /** The modifiers that the release of the [n]th typed [kv] carried to the IME. */
    private fun modsOfUp(kv: KeyValue, n: Int = 0) = handler.ups.filter { it.first == kv }[n].second

    @Test
    fun aModifierHeldWhileAKeyIsTappedAppliesToIt() {
        for (kv in listOf(shift, ctrl, KeyValue.getKeyByName("alt"), fn, KeyValue.getKeyByName("meta"))) {
            ptrs.clear()
            handler.ups.clear()
            pressWith(FINGER, keyFor(kv))
            tapWith(OTHER_FINGER, letterA)
            assertWithMessage("$kv held while a is tapped").that(modsOfUp(letterA.keys[0]!!).has(kv.getModifier())).isTrue()
            releaseFinger(FINGER)
            assertWithMessage("$kv ends with its finger").that(pointersFor(kv)).isEmpty()
            assertThat(pointers.getModifiers().has(kv.getModifier())).isFalse()
        }
    }

    @Test
    fun everyKeyTypedDuringTheHoldGetsIt() {
        pressWith(FINGER, shiftKey)
        tapWith(OTHER_FINGER, letterA)
        tapWith(OTHER_FINGER, letterB)
        assertThat(modsOfUp(letterA.keys[0]!!).has(KeyValue.Modifier.SHIFT)).isTrue()
        assertThat(modsOfUp(letterB.keys[0]!!).has(KeyValue.Modifier.SHIFT)).isTrue()
        releaseFinger(FINGER)
        assertThat(pointersFor(shift)).isEmpty()
    }

    @Test
    fun rollingOverFromShiftIntoTheNextLetterCapitalisesIt() {
        // A fast typist's Shift lifts after the next letter went down.
        pressWith(FINGER, shiftKey)
        pressWith(OTHER_FINGER, letterA)
        releaseFinger(FINGER)
        releaseFinger(OTHER_FINGER)
        assertThat(modsOfUp(letterA.keys[0]!!).has(KeyValue.Modifier.SHIFT)).isTrue()
        assertWithMessage("Shift is spent, not left latched").that(pointersFor(shift)).isEmpty()
    }

    @Test
    fun twoHeldModifiersBothApply() {
        pressWith(FINGER, ctrlKey)
        pressWith(OTHER_FINGER, shiftKey)
        tapWith(THIRD_FINGER, letterA)
        val mods = modsOfUp(letterA.keys[0]!!)
        assertThat(mods.has(KeyValue.Modifier.CTRL)).isTrue()
        assertThat(mods.has(KeyValue.Modifier.SHIFT)).isTrue()
    }

    @Test
    fun aTappedModifierAndAHeldOneBothApply() {
        // Ctrl tapped (latched), then Shift held while t is tapped: Ctrl+Shift+T.
        tap(ctrlKey)
        pressWith(FINGER, shiftKey)
        tapWith(OTHER_FINGER, letterA)
        val mods = modsOfUp(letterA.keys[0]!!)
        assertThat(mods.has(KeyValue.Modifier.CTRL)).isTrue()
        assertThat(mods.has(KeyValue.Modifier.SHIFT)).isTrue()
        releaseFinger(FINGER)
        assertWithMessage("the one-shot Ctrl is spent, and Shift lifted").that(pointers.getModifiers().size()).isEqualTo(0)
    }

    @Test
    fun aModifierTappedDuringAHoldStaysForTheNextKey() {
        // Hold Shift, tap Ctrl, lift Shift, then tap a: the one-shot Ctrl is still waiting for a.
        // Shift's release used to clear it (a held modifier's release cleared latches as a typed
        // key's does).
        pressWith(FINGER, shiftKey)
        tapWith(OTHER_FINGER, ctrlKey)
        releaseFinger(FINGER)
        tapWith(OTHER_FINGER, letterA)
        val mods = modsOfUp(letterA.keys[0]!!)
        assertWithMessage("Ctrl tapped during the hold applies to the next key").that(mods.has(KeyValue.Modifier.CTRL)).isTrue()
        assertWithMessage("Shift was lifted").that(mods.has(KeyValue.Modifier.SHIFT)).isFalse()
        assertThat(pointers.getModifiers().size()).isEqualTo(0)
    }

    @Test
    fun aModifierThatTypedSomethingOpensNoPopoverAndLocksNothing() {
        useSnapshot(snap(popover = true))
        pressWith(FINGER, shiftWithSubkeys)
        val shiftsTimer = ptrs.single { it.pointerId == FINGER }.timeoutWhat
        tapWith(OTHER_FINGER, letterA)
        // The timer the Handler had for Shift's press, were it still due.
        pointers.handleMessage(message(shiftsTimer))
        assertWithMessage("no popover after the chord").that(handler.shown).isNull()
        assertThat(pointers.getKeyFlags(shift).let { if (it == -1) 0 else it and FLAG_P_LOCKED }).isEqualTo(0)
        releaseFinger(FINGER)
        assertThat(pointersFor(shift)).isEmpty()
    }

    @Test
    fun typingWithAnotherFingerOnAnOpenPopoverAppliesTheModifier() {
        useSnapshot(snap(popover = true))
        hold(shiftWithSubkeys)
        assertThat(handler.shown).isNotNull()
        tapWith(OTHER_FINGER, letterA) // closes the popover, as any second finger does
        assertThat(modsOfUp(letterA.keys[0]!!).has(KeyValue.Modifier.SHIFT)).isTrue()
        release()
        assertThat(pointersFor(shift)).isEmpty()
        assertThat(pointers.getModifiers().has(KeyValue.Modifier.SHIFT)).isFalse()
    }

    @Test
    fun aLockedShiftStaysLockedThroughAChordOnIt() {
        longPress(shiftKey) // caps lock (popover off)
        pressWith(FINGER, shiftKey)
        tapWith(OTHER_FINGER, letterA)
        releaseFinger(FINGER)
        assertLockedByTheUser(shift)
    }

    @Test
    fun aShiftTouchedAloneStillLeavesTheLettersAlone() {
        // v1.2.2: a finger resting on Shift (a short swipe on it) does not shift the drawn keys.
        press(shiftKey)
        assertThat(pointers.getModifiers().has(KeyValue.Modifier.SHIFT)).isFalse()
    }

    // ------------------------------------------------------------------ unchanged

    @Test
    fun holdingShiftWithNothingLatchedLocksIt() {
        longPress(shiftKey)
        assertLockedByTheUser(shift)
    }

    @Test
    fun aTapOnAutoCapitalisationsShiftStillReleasesIt() {
        autocapLatchesShift()
        tap(shiftKey)
        assertThat(pointersFor(shift)).isEmpty()
        assertThat(pointers.getModifiers().has(KeyValue.Modifier.SHIFT)).isFalse()
    }

    @Test
    fun aTapOnALockedShiftUnlocksIt() {
        longPress(shiftKey)
        tap(shiftKey)
        assertThat(pointersFor(shift)).isEmpty()
    }

    @Test
    fun doubleTappingShiftStillLocksIt() {
        tap(shiftKey)
        tap(shiftKey)
        assertLockedByTheUser(shift)
    }

    // ------------------------------------------------------------------ harness

    /** compose_data.bin for the Compose key, loaded as [CtrlShiftLabelTest] does. */
    private fun loadComposeData() {
        val bin = java.io.File("src/main/assets/compose_data.bin")
        assertWithMessage("run generateComposeData first: ${bin.absolutePath}").that(bin.exists()).isTrue()
        val assets = mockk<android.content.res.AssetManager>()
        every { assets.open("compose_data.bin") } answers { java.io.FileInputStream(bin) }
        val context = mockk<android.content.Context>()
        every { context.assets } returns assets
        ComposeKeyData.initialize(context) // idempotent
    }

    private class RecordingHandler : Pointers.IPointerEventHandler {
        val haptics = mutableListOf<HapticEvent>()
        /** Every key release and the modifiers it carried. */
        val ups = mutableListOf<Pair<KeyValue?, Pointers.Modifiers>>()
        override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? = k
        override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) {}
        override fun onPointerUp(k: KeyValue?, mods: Pointers.Modifiers) { ups += k to mods }
        override fun onPointerFlagsChanged(hapticEvent: HapticEvent?) { hapticEvent?.let { haptics += it } }
        override fun onPointerHold(k: KeyValue, mods: Pointers.Modifiers) {}
        override fun onSwipeMove(x: Float, y: Float, recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun onSwipeEnd(recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun isShiftLocked(): Boolean = false
        override fun isPointWithinKey(x: Float, y: Float, key: KeyboardData.Key): Boolean = true
        override fun isPointWithinKeyWithTolerance(x: Float, y: Float, key: KeyboardData.Key, tolerance: Float) = true
        override fun getKeyHypotenuse(key: KeyboardData.Key): Float = 180f
        override fun getKeyWidth(key: KeyboardData.Key): Float = 100f
        override fun onCustomShortSwipe(mapping: ShortSwipeMapping) {}

        var shown: SubkeyPopoverState? = null
        var touchExploration = false
        override fun subkeyPopoverMetrics(key: KeyboardData.Key) = SubkeyPopoverMetrics(100f, 150f, 1000f, 600f)
        override fun onSubkeyPopoverShow(state: SubkeyPopoverState) { shown = state }
        override fun isTouchExplorationEnabled(): Boolean = touchExploration
    }

    /** A [Message] with only `what` set; android.jar's constructor is a stub, so allocate raw. */
    private fun message(what: Int): Message =
        objenesis.newInstance(Message::class.java).also { Message::class.java.getField("what").setInt(it, what) }

    /** Sets a declared field anywhere up the class chain. */
    private fun Any.setField(name: String, value: Any?) {
        var type: Class<*>? = javaClass
        var field: Field? = null
        while (type != null && field == null) {
            field = type.declaredFields.firstOrNull { it.name == name }
            type = type.superclass
        }
        requireNotNull(field) { "no field $name on ${javaClass.name}" }.apply { isAccessible = true }.set(this, value)
    }

    private companion object {
        const val FINGER = 0
        const val OTHER_FINGER = 1
        const val THIRD_FINGER = 2
    }
}

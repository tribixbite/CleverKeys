package tribixbite.cleverkeys

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.backup.crypto.BackupPassphraseStore

/**
 * The backup-password UI keeps up with the password, found on the Note10+ and a Saga (2026-11-24):
 *
 *  - right after a password was set, "Export unencrypted…" was missing until Settings was reopened:
 *    the section asked the store directly, which no recomposition followed;
 *  - after a wrong password, the import prompt kept "Wrong backup password…" under the field while
 *    the next password was typed.
 *
 * Starts each test with no backup password and puts the device's own one back afterwards.
 */
@RunWith(AndroidJUnit4::class)
class BackupPasswordUiStateTest {

    @get:Rule
    val compose = createAndroidComposeRule<SettingsActivity>()

    private var savedPassphrase: CharArray? = null

    private fun text(id: Int): String = compose.activity.getString(id)

    private fun inDialog(label: String) = hasText(label) and hasAnyAncestor(isDialog())

    @Before
    fun startWithoutAPassword() {
        val store = BackupPassphraseStore(InstrumentationRegistry.getInstrumentation().targetContext)
        savedPassphrase = store.getPassphrase()
        store.clear()
        // The section reads the store when it is first composed; start from a fresh one.
        compose.activityRule.scenario.recreate()
    }

    @After
    fun restoreThePassword() {
        val store = BackupPassphraseStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.clear()
        savedPassphrase?.let {
            store.setPassphrase(it)
            it.fill(' ')
        }
    }

    private fun openBackupSection() {
        compose.onNodeWithText(text(R.string.settings_section_backup_restore), substring = true)
            .performScrollTo()
            .performClick()
    }

    private fun setPassword(pass: String) {
        compose.onNodeWithText(text(R.string.backup_password_set)).performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.backup_password_new_label)) and hasSetTextAction())
            .performTextInput(pass)
        compose.onNode(hasText(text(R.string.backup_password_confirm_label)) and hasSetTextAction())
            .performTextInput(pass)
        compose.onNode(inDialog(text(R.string.common_save)) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    @Test
    fun theUnencryptedExportOfferAppearsOnceAPasswordIsSet() {
        openBackupSection()
        compose.onNodeWithText(text(R.string.backup_section_plaintext_offer)).assertDoesNotExist()

        setPassword("ui-state-test-passphrase")

        compose.onNodeWithText(text(R.string.backup_section_plaintext_offer))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theArmedLabelEndsWhenAnExportUsesIt() {
        openBackupSection()
        setPassword("ui-state-test-passphrase")
        compose.onNodeWithText(text(R.string.backup_section_plaintext_offer)).performScrollTo().performClick()
        compose.onNode(inDialog(text(R.string.backup_section_plaintext_dialog_confirm))).performClick()
        compose.onNodeWithText(text(R.string.backup_section_plaintext_armed))
            .performScrollTo()
            .assertIsDisplayed()

        // What the next export does when it starts (SettingsBackupHandlers.exportPolicy).
        compose.runOnUiThread { compose.activity.consumePlaintextOptOut() }

        compose.onNodeWithText(text(R.string.backup_section_plaintext_offer))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.backup_section_plaintext_armed)).assertDoesNotExist()
    }

    @Test
    fun typingANewPasswordClearsTheWrongPasswordError() {
        val wrong = text(IoFailureReason.WRONG_PASSWORD_OR_CORRUPT.messageRes)
        compose.runOnUiThread {
            val vm = compose.activity.backupRestoreViewModel
            vm.pendingPassphraseImport = PendingPassphraseImport(
                EncryptedImportKind.CLIPBOARD_ZIP,
                Uri.parse("content://tribixbite.cleverkeys.test/backup.zip.ckenc"),
            )
            vm.passphrasePromptError = wrong
        }
        compose.onNodeWithText(wrong).assertIsDisplayed()

        compose.onNode(hasText(text(R.string.backup_passphrase_prompt_label)) and hasSetTextAction())
            .performTextInput("w")

        compose.onNodeWithText(wrong).assertDoesNotExist()
        // The prompt stays open for the new attempt; Cancel leaves no import pending.
        compose.onNode(inDialog(text(R.string.common_cancel)) and hasClickAction()).performClick()
        compose.runOnIdle {
            check(compose.activity.backupRestoreViewModel.pendingPassphraseImport == null)
        }
    }
}

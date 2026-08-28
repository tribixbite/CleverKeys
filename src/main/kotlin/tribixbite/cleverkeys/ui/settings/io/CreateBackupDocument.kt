package tribixbite.cleverkeys.ui.settings.io

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import tribixbite.cleverkeys.backup.BackupExportName

/**
 * ARC-035: `ACTION_CREATE_DOCUMENT` contract whose MIME type is chosen **per launch** instead of
 * being fixed at registration.
 *
 * `ActivityResultContracts.CreateDocument` takes its MIME type as a constructor argument, so a
 * single registered launcher can only ever ask for one type. Encrypted exports need
 * `application/octet-stream` (so the picker preserves the `.ckenc` suffix — see
 * [tribixbite.cleverkeys.backup.BackupExportNaming]) while plaintext exports keep
 * `application/json` / `application/zip`, and which one applies is only known when the user taps
 * Export. Registering two launchers per export would double the boilerplate for every exporter;
 * one input-driven contract carries the decision instead.
 *
 * Intent shape is deliberately identical to `CreateDocument`'s (`setType` + `EXTRA_TITLE`, no
 * `CATEGORY_OPENABLE`), so picker behavior is unchanged apart from the type.
 */
class CreateBackupDocument : ActivityResultContract<BackupExportName, Uri?>() {

    override fun createIntent(context: Context, input: BackupExportName): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}

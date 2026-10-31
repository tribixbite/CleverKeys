package tribixbite.cleverkeys.legal

import android.content.res.AssetManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import org.junit.Test
import tribixbite.cleverkeys.OpenSourceLicensesActivity

/**
 * The Activity's AssetManager glue ([OpenSourceLicensesActivity.readAssetText]) over the assets the
 * build actually generates (`build/generated/licenseAssets`, runMockTests depends on
 * `generateLicenseAssets`): every NOTICE section and every licence text reaches the screen
 * model, and an asset the APK lacks becomes a "missing" entry instead of an exception that
 * would close the screen.
 */
class OpenSourceLicensesAssetReadTest {

    /** AssetManager stub serving the generated asset directory, like the APK's assets/. */
    private fun generatedAssets(except: String? = null): AssetManager {
        val assets = mockk<AssetManager>()
        every { assets.open(any()) } answers {
            val path = firstArg<String>()
            val file = File(GENERATED_DIR, path)
            if (path == except || !file.isFile) throw FileNotFoundException(path)
            FileInputStream(file)
        }
        return assets
    }

    @Test
    fun everyNoticeSectionAndLicenceTextIsReadFromTheAssets() {
        assertWithMessage("$GENERATED_DIR missing — generateLicenseAssets did not run")
            .that(File(GENERATED_DIR, OpenSourceLicenses.NOTICE_ASSET).isFile).isTrue()
        val assets = generatedAssets()

        val notice = OpenSourceLicensesActivity.readAssetText(assets, OpenSourceLicenses.NOTICE_ASSET)
        assertThat(notice).isEqualTo(File("NOTICE").readText())

        val screen = OpenSourceLicenses.buildScreen { OpenSourceLicensesActivity.readAssetText(assets, it) }
        val titles = OpenSourceLicenses.parseNotice(File("NOTICE").readText()).sections.map { it.title }
        assertThat(screen.entries.filter { it.kind == OpenSourceLicenses.Kind.NOTICE_SECTION }.map { it.title })
            .containsExactlyElementsIn(titles).inOrder()
        val texts = screen.entries.filter { it.kind == OpenSourceLicenses.Kind.LICENSE_TEXT }
        assertThat(texts).hasSize(OpenSourceLicenses.LICENSES.size)
        texts.forEach { assertWithMessage(it.title).that(it.missing).isFalse() }
    }

    @Test
    fun anAssetTheApkLacksIsReportedAsMissing() {
        val lgpl = OpenSourceLicenses.LICENSES.single { it.spdxId == "LGPL-3.0-only" }
        val assets = generatedAssets(except = lgpl.assetPath)

        assertThat(OpenSourceLicensesActivity.readAssetText(assets, lgpl.assetPath)).isNull()
        val screen = OpenSourceLicenses.buildScreen { OpenSourceLicensesActivity.readAssetText(assets, it) }
        assertThat(screen.entries.single { it.key == "license:LGPL-3.0-only" }.missing).isTrue()
        assertThat(screen.entries.count { it.missing }).isEqualTo(1)
    }

    private companion object {
        const val GENERATED_DIR = "build/generated/licenseAssets"
    }
}

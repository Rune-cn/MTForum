// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.rune.mt.forum.ui.page.forum.AttachmentSaveResult
import io.rune.mt.forum.ui.page.forum.saveAttachmentToDownloads
import io.rune.mt.forum.ui.page.forum.shareFile
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "downloaded an attachment, then 保存/分享 did nothing" report.
 *
 * Root cause: sharing built its payload with `Uri.fromFile(...)`. Since API 24 a `file://` URI sent
 * to another app throws `FileUriExposedException`, and the whole call sat inside `runCatching`, so
 * the exception vanished and the button looked dead.
 *
 * The fix hands over a `content://` URI from the app's `FileProvider`. Robolectric does not expand
 * `@xml/file_paths` provider meta-data (its `FileProvider.getUriForFile` resolves no roots), so the
 * export path itself is pinned by two things that *are* checkable here:
 *
 *  - this test scans the shipping source: no `Uri.fromFile`, and `FileProvider.getUriForFile` with
 *    `FLAG_GRANT_READ_URI_PERMISSION`;
 *  - it checks the manifest declares the provider with `grantUriPermissions="true"` and that
 *    `file_paths.xml` exposes the attachments cache root.
 *
 * A missing file must still report a failure rather than doing nothing, which is a real runtime
 * assertion. Everything that needs the real chooser (and the grant) is on-device work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class)
class AttachmentShareTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun source(relative: String): String {
        // Tests run with the module dir as the working directory; the source tree is two levels up
        // from `app/build/...`, but Gradle's test working dir is the module, so `src/...` resolves.
        val candidates = listOf(
            File("src/main/$relative"),
            File("app/src/main/$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("cannot locate $relative from ${File(".").absolutePath}")
    }

    @Test
    fun `sharing uses the file provider and never a file uri`() {
        val code = source("java/io/rune/mt/forum/ui/page/forum/AttachmentDownload.kt")
        assertFalse(
            "a file:// URI is rejected by every API >= 24 (FileUriExposedException)",
            code.contains("Uri.fromFile"),
        )
        assertTrue(
            "sharing must go through FileProvider.getUriForFile",
            code.contains("FileProvider.getUriForFile"),
        )
        assertTrue(
            "receivers need FLAG_GRANT_READ_URI_PERMISSION",
            code.contains("FLAG_GRANT_READ_URI_PERMISSION"),
        )
        assertTrue(
            "the share result must be returned, not swallowed by runCatching",
            code.contains("exceptionOrNull()"),
        )
    }

    @Test
    fun `the manifest declares the provider and the paths expose the attachment cache`() {
        val manifest = source("AndroidManifest.xml")
        assertTrue(
            "FileProvider must be declared with grantUriPermissions",
            manifest.contains("androidx.core.content.FileProvider") &&
                manifest.contains("grantUriPermissions=\"true\""),
        )
        assertTrue(
            "the provider authority must be app-scoped",
            manifest.contains("\${applicationId}.fileprovider"),
        )
        val paths = source("res/xml/file_paths.xml")
        assertTrue(
            "the attachments cache root must be exposed",
            paths.contains("name=\"attachments\"") && paths.contains("path=\"attachments/\""),
        )
    }

    @Test
    fun `a missing file is reported instead of silently doing nothing`() {
        val missing = File(context.cacheDir, "attachments/not-downloaded.zip")
        val failure = shareFile(context, missing, "not-downloaded.zip", "Share")
        assertNotNull("a missing file must surface a failure, not a no-op", failure)
    }

    @Test
    fun `saving reports a location the user can find`() {
        val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
        val file = File(dir, "plan.png").apply { writeText("payload") }
        when (val result = saveAttachmentToDownloads(context, file, "plan.png")) {
            is AttachmentSaveResult.Saved -> assertTrue(
                "the label must name a real location, was ${result.label}",
                result.label.isNotBlank(),
            )

            is AttachmentSaveResult.Failed -> throw AssertionError("save failed: ${result.reason}")
        }
    }
}
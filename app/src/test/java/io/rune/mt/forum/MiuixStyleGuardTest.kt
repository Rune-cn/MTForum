// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-contract regression test for the OS3 / OS4 style boundary.
 *
 * The app ships two Miuix presentations behind one switch ([io.rune.mt.forum.ui.theme.AppTheme]
 * `useMiuixOs4`, resolved from the platform's render-effect support). A widget from the other
 * presentation is not a cosmetic difference: `Glass*` widgets are inline overlays that need the
 * root `MiuixComponentHost` (and a backdrop layer that must not sample itself), while
 * `Window*` widgets are real OS3 dialog windows. Mixing them silently puts the wrong chrome on
 * the wrong path, and a screenshot on one style cannot catch it on the other.
 *
 * This test reads the shipping sources (Robolectric cannot see “which composable was selected
 * from where”) and pins two rules:
 *
 *  1. every file that imports an OS4-only glass widget must also mention the style switch
 *     (`AppTheme.useMiuixOs4` / `MiuixComponentScope` / an OS4 branch parameter) in that same
 *     file, unless it *is* the OS4-only wrapper itself (allowlisted below);
 *  2. every file that imports an OS3-only window widget must likewise be a style-aware file, so
 *     a classic sheet/dialog that was copied straight from an OS3 screen fails here.
 *
 * A third check keeps Material 3 colours out of the Miuix `ui` tree. `MiuixCards` used
 * `MaterialTheme.colorScheme.outline` on a divider while every sibling drew `MiuixTheme`; under
 * OS4 the two palettes are not the same surface, so the leak was visible.
 *
 * Non-zero exits are not available in a JUnit contract, hence the inverted assertion messages:
 * the failure text lists every offending file.
 */
class MiuixStyleGuardTest {

    private fun sourceRoot(relative: String = "java/io/rune/mt/forum"): File {
        val candidates = listOf(
            File("src/main/$relative"),
            File("app/src/main/$relative"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("cannot locate $relative from ${File(".").absolutePath}")
    }

    private fun kotlinFiles(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()

    private fun File.readSource(): String = readText()

    /**
     * OS4-only surface. `top.yukonga.miuix.kmp.glass` is the package the glass components live in;
     * `GlassDefaults` / `GlassShadows` come from `miuix-material` and are part of the same
     * presentation. Import lines are used (not raw mentions) so a KDoc example does not trip the
     * contract.
     */
    private val os4Import = Regex(
        """(?m)^import top\.yukonga\.miuix\.kmp\.glass\.""" +
            "|" +
            """(?m)^import top\.yukonga\.miuix\.kmp\.material\.Glass(?:Defaults|Shadows)$""",
    )

    /** OS3-only surface: real dialog windows / popups. */
    private val os3Import = Regex(
        """(?m)^import top\.yukonga\.miuix\.kmp\.window\.Window(?:ListPopup|Dialog|BottomSheet)$""" +
            "|" +
            """(?m)^import io\.rune\.mt\.forum\.ui\.library\.FloatingBottomBar$""",
    )

    /** One of these tokens means the file itself branches on the style switch. */
    private val guardTokens = listOf(
        "AppTheme.useMiuixOs4",
        "useMiuixOs4",
        "MiuixComponentScope",
        "useOs4",
    )

    private fun String.hasStyleGuard(): Boolean {
        // Comments must not satisfy the contract: a KDoc that merely names `useMiuixOs4` is not a
        // branch, and the earlier version of this test could be fooled by exactly that.
        val code = replace(Regex("""(?s)/\*.*?\*/"""), "")
            .replace(Regex("""(?m)//.*$"""), "")
        return guardTokens.any { code.contains(it) }
    }

    /**
     * The OS4-only wrapper has to import the glass package — that is its whole job. It is called
     * only from the `useOs4` branch (`MiuixSettingsPage`), so the guard lives at the call site.
     * Any other file without a guard is a leak.
     */
    private val os4ImportAllowlist = setOf("MiuixGlassNavigationBar.kt")

    @Test
    fun `os4 glass widgets are only used behind a style guard`() {
        val files = kotlinFiles(sourceRoot())
        assertTrue("source scan found too few files: ${files.size}", files.size > 100)
        val offenders = files.filter { file ->
            file.name !in os4ImportAllowlist && file.readSource().let { code ->
                os4Import.containsMatchIn(code) && !code.hasStyleGuard()
            }
        }
        assertTrue(
            "OS4-only glass imports without a useMiuixOs4/MiuixComponentScope guard: " +
                offenders.map { it.name }.sorted(),
            offenders.isEmpty(),
        )
        // Keep the allowlist honest: it must still name a real OS4 wrapper, not a file whose glass
        // import has since been removed.
        for (name in os4ImportAllowlist) {
            val file = files.first { it.name == name }
            assertTrue(
                "$name is allowlisted as the OS4 wrapper but no longer imports OS4 glass",
                os4Import.containsMatchIn(file.readSource()),
            )
        }
    }

    @Test
    fun `os3 window widgets are only used behind a style guard`() {
        val files = kotlinFiles(sourceRoot())
        val offenders = files.filter { file ->
            file.readSource().let { code ->
                os3Import.containsMatchIn(code) && !code.hasStyleGuard()
            }
        }
        assertTrue(
            "OS3-only window widgets without a useMiuixOs4 branch: " +
                offenders.map { it.name }.sorted(),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `miuix screens do not read Material 3 colors`() {
        val uiRoot = sourceRoot("java/io/rune/mt/forum/ui")
        val files = kotlinFiles(uiRoot)
        assertTrue("ui source scan found too few files: ${files.size}", files.size > 50)
        val material3 = Regex("MaterialTheme\\.colorScheme|androidx\\.compose\\.material3\\.LocalTextStyle")
        val offenders = files.filter { file ->
            val code = file.readSource()
                .replace(Regex("""(?s)/\*.*?\*/"""), "")
                .replace(Regex("""(?m)//.*$"""), "")
            material3.containsMatchIn(code)
        }
        assertTrue(
            "Material 3 theme reads inside the Miuix UI tree (use MiuixTheme instead): " +
                offenders.map { it.name }.sorted(),
            offenders.isEmpty(),
        )
    }
}
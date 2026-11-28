// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins the launcher icon: an adaptive icon (v26 XML in MIPMAP) whose foreground is the black
 * corner cat with amber eyes, plus the Android 13 themed layer.
 *
 * There is no design tool on the build machine, so the art is only vector XML. That makes two
 * classes of mistake cheap to ship and impossible to see without installing the APK: a face that a
 * mask clips and a themed layer that is not a flat silhouette. Both are checked against the
 * RENDERED pixels, so they test what the launcher draws instead of what the XML says.
 *
 * The artwork is a close-up: the body runs off the bottom-left edge on purpose, so body pixels may
 * sit outside the mask. The face must survive every launcher shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun source(relative: String): String {
        // Gradle runs unit tests with the module directory as the working directory.
        val candidates = listOf(
            File("src/main/$relative"),
            File("app/src/main/$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("cannot locate $relative")
    }

    private fun render(drawableRes: Int): Bitmap {
        val drawable: Drawable = requireNotNull(context.getDrawable(drawableRes)) {
            "missing drawable $drawableRes"
        }
        val bitmap = Bitmap.createBitmap(RENDER_PX, RENDER_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, RENDER_PX, RENDER_PX)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(RENDER_PX * RENDER_PX).also {
            bitmap.getPixels(it, 0, RENDER_PX, 0, 0, RENDER_PX, RENDER_PX)
        }

    private fun alpha(pixel: Int): Int = (pixel ushr 24) and 0xFF

    private fun red(pixel: Int): Int = (pixel shr 16) and 0xFF

    private fun green(pixel: Int): Int = (pixel shr 8) and 0xFF

    private fun blue(pixel: Int): Int = pixel and 0xFF

    private fun dp(px: Float): Float = px * CANVAS_DP / RENDER_PX

    @Test
    fun `launcher is an adaptive icon with a themed monochrome layer`() {
        // Runtime check: the manifest points at @mipmap/ic_launcher(_round), and those resolve to a
        // real AdaptiveIconDrawable rather than a bitmap left over from the old art.
        val icon = context.getDrawable(R.mipmap.ic_launcher)
        val roundIcon = context.getDrawable(R.mipmap.ic_launcher_round)
        assertTrue("ic_launcher must resolve to an adaptive icon, was $icon", icon is AdaptiveIconDrawable)
        assertTrue("ic_launcher_round must resolve to an adaptive icon, was $roundIcon", roundIcon is AdaptiveIconDrawable)

        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val xml = source("res/mipmap-anydpi-v26/$name.xml")
            assertTrue("$name must be an <adaptive-icon>", xml.contains("<adaptive-icon"))
            assertTrue(
                "$name must name the green field as background",
                xml.contains("<background") && xml.contains("@drawable/ic_launcher_background"),
            )
            assertTrue(
                "$name must name the cat as foreground",
                xml.contains("<foreground") && xml.contains("@drawable/ic_launcher_foreground"),
            )
            assertTrue(
                "$name needs <monochrome> or Android 13 themed icons fall back to a grey tile",
                xml.contains("<monochrome") && xml.contains("@drawable/ic_launcher_monochrome"),
            )
        }
    }

    @Test
    fun `manifest launches with the vector icon and nothing from the old art`() {
        val manifest = source("AndroidManifest.xml")
        assertTrue(
            "the application must use @mipmap/ic_launcher",
            manifest.contains("android:icon="@mipmap/ic_launcher""),
        )
        assertTrue(
            "the application must use @mipmap/ic_launcher_round",
            manifest.contains("android:roundIcon="@mipmap/ic_launcher_round""),
        )
        // minSdk is 26, so an adaptive XML is enough on every supported device: a stray raster
        // mipmap-*dpi/ic_launcher.png would silently win over the vector for that density.
        val resDir = File("src/main/res").let { if (it.exists()) it else File("app/src/main/res") }
        val launcherArt = resDir
            .walkTopDown()
            .filter { it.isFile && it.name.startsWith("ic_launcher") }
            .map { it.relativeTo(resDir).path.replace('\\', '/') }
            .toSet()
        assertEquals(
            "the launcher icon should be exactly the three vectors plus the two adaptive XMLs",
            setOf(
                "drawable/ic_launcher_background.xml",
                "drawable/ic_launcher_foreground.xml",
                "drawable/ic_launcher_monochrome.xml",
                "mipmap-anydpi-v26/ic_launcher.xml",
                "mipmap-anydpi-v26/ic_launcher_round.xml",
            ),
            launcherArt,
        )
        val foreground = source("res/drawable/ic_launcher_foreground.xml")
        assertTrue(
            "the cat must stay a 108dp vector so it scales without a raster fallback",
            foreground.contains("android:viewportWidth="108"") &&
                foreground.contains("android:viewportHeight="108""),
        )
        assertTrue("the fur is the artwork's near-black", foreground.contains("#FF050505"))
        assertTrue("the eyes are the artwork's amber", foreground.contains("#FFFECB4C"))
    }

    @Test
    fun `the face stays inside the 72dp window launchers show`() {
        // The body is allowed to run off the bottom-left edge; the face is not. A launcher shape
        // keeps the central 72dp window (18..90dp on both axes), so the amber eyes must stay within
        // 36dp of the centre on each axis.
        val data = pixels(render(R.drawable.ic_launcher_foreground))
        val centre = RENDER_PX / 2f
        var ink = 0
        var eyes = 0
        var worst = 0f
        for (y in 0 until RENDER_PX) {
            for (x in 0 until RENDER_PX) {
                val pixel = data[y * RENDER_PX + x]
                if (alpha(pixel) < 128) continue
                ink++
                if (red(pixel) > 150 && green(pixel) > 110 && blue(pixel) < 160) {
                    eyes++
                    worst = maxOf(
                        worst,
                        abs(dp(x + 0.5f - centre)),
                        abs(dp(y + 0.5f - centre)),
                    )
                }
            }
        }
        assertTrue("the foreground must draw something", ink > 10_000)
        assertTrue("the amber eyes must be there, found $eyes px", eyes > 2_000)
        assertTrue(
            "the eyes reach " + worst + "dp off centre on one axis; a 72dp mask keeps 36dp",
            worst <= 36f,
        )
    }

    @Test
    fun `the cat sits in the bottom left and keeps the top right calm`() {
        val data = pixels(render(R.drawable.ic_launcher_foreground))
        var total = 0
        var left = 0
        var bottom = 0
        var topRight = 0
        for (y in 0 until RENDER_PX) {
            for (x in 0 until RENDER_PX) {
                if (alpha(data[y * RENDER_PX + x]) < 40) continue
                total++
                if (x < RENDER_PX / 2) left++
                if (y >= RENDER_PX / 2) bottom++
                if (x >= RENDER_PX / 2 && y < RENDER_PX / 2) topRight++
            }
        }
        assertTrue("the foreground must draw something", total > 10_000)
        assertTrue(
            "the cat belongs to the left half, only $left/$total px are left of the centre line",
            left >= total * 0.5,
        )
        assertTrue(
            "the cat belongs to the bottom half, only $bottom/$total px are below the centre line",
            bottom >= total * 0.6,
        )
        // The right ear and the whisker tips reach into the upper-right corner by design, so this is
        // a "stay calm" budget rather than an emptiness rule.
        assertTrue(
            "the top-right quadrant is reserved for launcher badges, $topRight/$total px leaked",
            topRight <= total * 0.10,
        )
    }

    @Test
    fun `the themed layer is one flat silhouette`() {
        val data = pixels(render(R.drawable.ic_launcher_monochrome))
        var ink = 0
        for (pixel in data) {
            val a = alpha(pixel)
            if (a < 8) continue
            ink++
            if (a < 128) continue
            val r = red(pixel)
            val g = green(pixel)
            val b = blue(pixel)
            assertTrue(
                "the monochrome layer is tinted by the system, so it must be a single white tone; " +
                    "found #" + Integer.toHexString(pixel),
                r == g && g == b && r >= 0xF0,
            )
        }
        assertTrue("the monochrome layer must carry the same cat shape", ink > 10_000)
        val themed: Drawable = requireNotNull(context.getDrawable(R.drawable.ic_launcher_monochrome)) {
            "the themed layer must resolve at runtime"
        }
        assertTrue(
            "the themed layer must be a full-size 108dp vector, was " + themed.intrinsicWidth,
            themed.intrinsicWidth > 0 && themed.intrinsicHeight > 0,
        )
    }

    @Test
    fun `the About page centres the mark instead of blowing up the corner icon`() {
        // The launcher keeps the cat in the bottom-left on purpose; an About-page header logo must
        // sit on the axis of the app name below it, so that surface gets a translated copy.
        val mark = source("res/drawable/ic_about_mark.xml")
        val launcher = source("res/drawable/ic_launcher_foreground.xml")
        val silhouettes = Regex("android:pathData=\"([^\"]+)\"").findAll(launcher)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("the foreground must carry the fur silhouette first", silhouettes.isNotEmpty())
        assertTrue(
            "the About mark must reuse the launcher silhouette verbatim",
            mark.contains(silhouettes.first()),
        )
        assertTrue(
            "the About mark must be the centred copy: wrap the paths in a <group translateX>",
            mark.contains("<group") && mark.contains("android:translateX="),
        )
        val page = source("java/io/rune/mt/forum/ui/page/miuix/settings/preferred/about/MiuixAboutPage.kt")
        assertTrue(
            "the About header must use the centred mark",
            page.contains("R.drawable.ic_about_mark"),
        )
        assertTrue(
            "the About header must not reuse the bottom-left launcher layer as a centred logo",
            !page.contains("R.drawable.ic_launcher_monochrome"),
        )
    }

    private companion object {
        private const val CANVAS_DP = 108f
        private const val RENDER_PX = 512
    }
}

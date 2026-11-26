// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import coil3.decode.DataSource
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.rune.mt.forum.core.image.buildForumImageLoader
import java.io.File
import okio.Buffer
import okio.FileSystem
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The forum image loader must route GIF bytes to a GIF decoder.
 *
 * `BitmapFactoryDecoder` can render a static first frame, so a GIF is not invisible today — it
 * just loses its animation, and a GIF whose first frame is blank/transparent renders as an empty
 * block. Most post pictures (`icdn.binmt.cc/....gif`), every smiley and the default avatar
 * (`noavatar_middle.gif`) are GIFs, so `coil-gif` is required for them to look right.
 *
 * Robolectric's native `BitmapFactory`/`ImageDecoder` cannot produce pixels in this environment
 * (every raster decode fails inside the shadow), so these tests assert the decoder *selection* —
 * `ComponentRegistry.newDecoder` is the real production step that decides which decoder handles a
 * GIF. A GIF landing on `BitmapFactoryDecoder` is the un-animated wiring; a GIF landing on a
 * `coil3.gif` decoder is the fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class)
class ForumImageDecoderTest {

    private fun asset(name: String): ByteArray {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readBytes()
    }

    /** Mirrors what the engine does after a network fetch: pick a decoder for the fetched bytes. */
    private fun decoderFor(bytes: ByteArray, mimeType: String): Decoder {
        val context = RuntimeEnvironment.getApplication()
        val loader = buildForumImageLoader(context)
        val source = ImageSource(source = Buffer().write(bytes), fileSystem = FileSystem.SYSTEM)
        val fetchResult = SourceFetchResult(
            source = source,
            mimeType = mimeType,
            dataSource = DataSource.NETWORK,
        )
        println(
            "IMAGE-DIAG mime=$mimeType registeredDecoders=" +
                loader.components.decoderFactories.map { it.javaClass.name },
        )
        return checkNotNull(loader.components.newDecoder(fetchResult, Options(context), loader)) {
            "no decoder claimed the bytes"
        }.also { (decoder, index) -> println("IMAGE-DIAG mime=$mimeType chosen=$index ${decoder.javaClass.name}") }.first
    }

    private fun isGifDecoder(decoder: Decoder): Boolean = decoder.javaClass.name.contains(".gif.")

    @Test
    fun `site gif picture is decoded by a gif decoder`() {
        val decoder = decoderFor(asset("image_feed_thread.gif"), "image/gif")
        assertTrue(
            "GIF must be handled by a coil3.gif decoder, got ${decoder.javaClass.name}",
            isGifDecoder(decoder),
        )
    }

    @Test
    fun `default avatar placeholder gif is decoded by a gif decoder`() {
        val decoder = decoderFor(asset("image_noavatar_middle.gif"), "image/gif")
        assertTrue(
            "noavatar_middle.gif must be handled by a coil3.gif decoder, got ${decoder.javaClass.name}",
            isGifDecoder(decoder),
        )
    }

    @Test
    fun `jpeg keeps using the plain bitmap decoder`() {
        val decoder = decoderFor(asset("image_avatar_middle.jpg"), "image/jpeg")
        assertTrue(
            "JPEG must not be captured by the GIF decoder, got ${decoder.javaClass.name}",
            !isGifDecoder(decoder),
        )
    }
}
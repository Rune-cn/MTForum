package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import java.io.File
import org.junit.Test

class AvatarDiagTest {
    private fun fixture(name: String): String {
        val p = "src/test/resources/forum/$name"
        return (File(p).takeIf { it.exists() } ?: File("app/$p")).readText()
    }

    @Test
    fun `diagnose avatar urls`() {
        val page = ForumHtmlParser.parseThreadPage(fixture("board_44.html"), fid = 44, page = 1)
        page.threads.forEachIndexed { i, t ->
            println("THREAD[$i] author=${t.authorName} avatar=[${t.authorAvatarUrl}]")
        }
        val guide = ForumHtmlParser.parseThreadPage(fixture("guide_newthread.html"), fid = 0, page = 1)
        guide.threads.take(12).forEachIndexed { i, t ->
            println("GUIDE[$i] author=${t.authorName} avatar=[${t.authorAvatarUrl}]")
        }
        val blankBoard = page.threads.count { it.authorAvatarUrl.isNullOrBlank() }
        val blankGuide = guide.threads.count { it.authorAvatarUrl.isNullOrBlank() }
        println("BLANK board=$blankBoard/${page.threads.size} guide=$blankGuide/${guide.threads.size}")
    }
}

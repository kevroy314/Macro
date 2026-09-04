package com.macropad.app

import com.macropad.app.ui.MarkdownInline
import com.macropad.app.ui.MarkdownInline.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownInlineTest {

    private fun plain(spans: List<Span>) =
        spans.joinToString("") {
            when (it) {
                is Span.Plain -> it.text
                is Span.Bold -> it.text
                is Span.Link -> it.text
            }
        }

    @Test
    fun `a markdown link becomes a link span`() {
        val spans = MarkdownInline.parse("See [Panera](https://panera.com/nutrition) for it")
        val link = spans.filterIsInstance<Span.Link>().single()
        assertEquals("Panera", link.text)
        assertEquals("https://panera.com/nutrition", link.url)
        assertEquals("See Panera for it", plain(spans))
    }

    @Test
    fun `bold markers are removed, not shown`() {
        val spans = MarkdownInline.parse("about **405 cal** total")
        assertEquals("405 cal", spans.filterIsInstance<Span.Bold>().single().text)
        assertEquals("about 405 cal total", plain(spans))
    }

    @Test
    fun `a bare url becomes a link labelled by its host`() {
        val spans = MarkdownInline.parse("source: https://www.panera.com/menu/x?y=1")
        val link = spans.filterIsInstance<Span.Link>().single()
        assertEquals("panera.com", link.text)
        assertEquals("https://www.panera.com/menu/x?y=1", link.url)
    }

    @Test
    fun `a url inside a markdown link is not also matched as a bare url`() {
        // The bug this guards: matching both splits the link and duplicates the URL.
        val spans = MarkdownInline.parse("[Panera](https://panera.com)")
        assertEquals(1, spans.size)
        assertEquals("Panera", (spans.single() as Span.Link).text)
    }

    @Test
    fun `several links in one reply all survive`() {
        val spans = MarkdownInline.parse(
            "[a](https://one.com) then [b](https://two.com) and [c](https://three.com)"
        )
        assertEquals(
            listOf("https://one.com", "https://two.com", "https://three.com"),
            spans.filterIsInstance<Span.Link>().map { it.url }
        )
    }

    @Test
    fun `text without markup is one plain span, unchanged`() {
        val text = "You have 643 calories and 91g protein left."
        val spans = MarkdownInline.parse(text)
        assertEquals(1, spans.size)
        assertTrue(spans.single() is Span.Plain)
        assertEquals(text, plain(spans))
    }

    @Test
    fun `mixed markup keeps the original reading order`() {
        val spans = MarkdownInline.parse("Try **soup** from [Panera](https://p.com) tonight")
        assertEquals("Try soup from Panera tonight", plain(spans))
    }

    @Test
    fun `blank input produces nothing`() {
        assertTrue(MarkdownInline.parse("").isEmpty())
        assertTrue(MarkdownInline.parse("   ").isEmpty())
    }

    @Test
    fun `unclosed markup is left alone rather than eaten`() {
        val text = "half **a sandwich and [a link that never closes"
        assertEquals(text, plain(MarkdownInline.parse(text)))
    }
}

package com.macropad.app.ui

/**
 * Just enough Markdown for what the assistant actually writes.
 *
 * Replies cite their sources as `[Panera](https://...)` and emphasise numbers with
 * `**`. Rendered through a plain Text those show as punctuation, which reads as a bug
 * — so this turns them into spans the UI can style and make tappable.
 *
 * Pure and separate from Compose so the parsing can be tested, because getting it
 * wrong garbles the text rather than failing visibly.
 */
object MarkdownInline {

    sealed class Span {
        data class Plain(val text: String) : Span()
        data class Bold(val text: String) : Span()
        data class Link(val text: String, val url: String) : Span()
    }

    private val LINK = Regex("""\[([^\]\n]+)]\((https?://[^)\s]+)\)""")
    private val BOLD = Regex("""\*\*([^*\n]+)\*\*""")
    private val BARE_URL = Regex("""(?<![(\w])(https?://[^\s)\]]+)""")

    /** Splits [text] into styled spans, in order. */
    fun parse(text: String): List<Span> {
        if (text.isBlank()) return emptyList()

        // Links first: a bare-URL match inside a markdown link would split it in two.
        val matches = (
            LINK.findAll(text).map { it to SpanKind.LINK } +
                BOLD.findAll(text).map { it to SpanKind.BOLD } +
                BARE_URL.findAll(text).map { it to SpanKind.BARE }
            )
            .sortedBy { it.first.range.first }
            .toList()

        val spans = mutableListOf<Span>()
        var cursor = 0

        for ((match, kind) in matches) {
            // Overlaps happen where a bare URL sits inside a link that was already
            // taken. Whichever started first wins; the rest is skipped.
            if (match.range.first < cursor) continue

            if (match.range.first > cursor) {
                spans += Span.Plain(text.substring(cursor, match.range.first))
            }
            spans += when (kind) {
                SpanKind.LINK -> Span.Link(match.groupValues[1], match.groupValues[2])
                SpanKind.BOLD -> Span.Bold(match.groupValues[1])
                SpanKind.BARE -> Span.Link(shorten(match.groupValues[1]), match.groupValues[1])
            }
            cursor = match.range.last + 1
        }

        if (cursor < text.length) spans += Span.Plain(text.substring(cursor))
        return spans
    }

    /** A bare URL is shown as its host — the full thing is unreadable in a chat bubble. */
    private fun shorten(url: String): String =
        url.removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('/')
            .ifBlank { url }

    private enum class SpanKind { LINK, BOLD, BARE }
}

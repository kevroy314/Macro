package com.macropad.app.ui

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color

private const val URL_TAG = "url"

/**
 * Renders the small subset of Markdown the assistant writes, with tappable sources.
 *
 * Assistant replies cite pages as `[Panera](https://…)`. Shown through a plain Text
 * those render as literal brackets and parentheses, which reads as broken output.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor, color) {
        buildAnnotated(text, linkColor)
    }
    val uriHandler = LocalUriHandler.current

    ClickableText(
        text = annotated,
        modifier = modifier,
        style = style.copy(color = color),
        onClick = { offset ->
            annotated.getStringAnnotations(URL_TAG, offset, offset)
                .firstOrNull()
                ?.let { runCatching { uriHandler.openUri(it.item) } }
        }
    )
}

private fun buildAnnotated(text: String, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        for (span in MarkdownInline.parse(text)) {
            when (span) {
                is MarkdownInline.Span.Plain -> append(span.text)
                is MarkdownInline.Span.Bold ->
                    withStyleSpan(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(span.text)
                    }
                is MarkdownInline.Span.Link -> {
                    pushStringAnnotation(URL_TAG, span.url)
                    withStyleSpan(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        )
                    ) { append(span.text) }
                    pop()
                }
            }
        }
    }

/** Local helper so the builder reads the same for every span kind. */
private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withStyleSpan(
    style: SpanStyle,
    block: () -> Unit
) {
    pushStyle(style)
    block()
    pop()
}

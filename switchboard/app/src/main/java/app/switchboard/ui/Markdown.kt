package app.switchboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small Markdown subset: fenced code, headings, bullets, **bold**, *italic*, `code`. Enough for chat replies. */
@Composable
fun MarkdownText(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    SelectionContainer {
        Column {
            splitFences(text).forEach { (isCode, body) ->
                if (isCode) {
                    Text(
                        body.trimEnd('\n'),
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = color,
                        modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth()
                            .background(codeBg, RoundedCornerShape(8.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                        softWrap = false,
                    )
                } else if (body.isNotBlank()) {
                    Text(inline(body.trim('\n'), codeBg), color = color, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun splitFences(text: String): List<Pair<Boolean, String>> {
    val parts = mutableListOf<Pair<Boolean, String>>()
    var inCode = false
    val sb = StringBuilder()
    for (line in text.lines()) {
        if (line.trimStart().startsWith("```")) {
            parts += inCode to sb.toString(); sb.clear(); inCode = !inCode
        } else sb.append(line).append('\n')
    }
    parts += inCode to sb.toString() // unterminated fence while streaming still renders as code
    return parts
}

private val inlineRe = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)\]\((https?://[^)\s]+)\)|(?<![*\w])\*(?!\s)(.+?)\*(?!\w)""")

private fun inline(block: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    block.lines().forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        var line = raw
        val heading = Regex("^(#{1,6})\\s+").find(line)
        if (heading != null) {
            line = line.substring(heading.range.last + 1)
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = (22 - heading.groupValues[1].length * 2).sp)) { spans(line, codeBg) }
            return@forEachIndexed
        }
        Regex("^(\\s*)[-*+]\\s+").find(line)?.let { m ->
            append(m.groupValues[1]); append("•  "); line = line.substring(m.range.last + 1)
        }
        spans(line, codeBg)
    }
}

private fun AnnotatedString.Builder.spans(line: String, codeBg: Color) {
    var pos = 0
    for (m in inlineRe.findAll(line)) {
        append(line.substring(pos, m.range.first))
        val (bold, code, linkText, linkUrl, italic) = m.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(code) }
            linkUrl.isNotEmpty() -> withLink(LinkAnnotation.Url(linkUrl, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) { append(linkText) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
        }
        pos = m.range.last + 1
    }
    append(line.substring(pos))
}

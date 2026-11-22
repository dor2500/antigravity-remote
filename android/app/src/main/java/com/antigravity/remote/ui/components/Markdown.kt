package com.antigravity.remote.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * Minimal Markdown renderer for agent replies on a small screen: headings, bullet and numbered
 * lists, fenced code, **bold**, *italic*, `inline code` and [links](...) (shown as their label).
 */
fun renderMarkdown(source: String, accent: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    var inFence = false
    val lines = source.trimEnd().split('\n')
    lines.forEachIndexed { index, rawLine ->
        val line = rawLine.trimEnd('\r')
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
            return@forEachIndexed
        }
        when {
            inFence -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, background = codeBackground)) {
                append(line)
            }
            HEADING.matches(line) -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp)) {
                appendInline(line.replace(HEADING_PREFIX, ""), accent, codeBackground)
            }
            BULLET.containsMatchIn(line) -> {
                val match = BULLET.find(line)!!
                append(" ".repeat(match.groupValues[1].length))
                append("\u2022 ")
                appendInline(line.substring(match.range.last + 1), accent, codeBackground)
            }
            else -> appendInline(line, accent, codeBackground)
        }
        if (index < lines.lastIndex) append('\n')
    }
}

private val HEADING = Regex("^#{1,6}\\s+.*")
private val HEADING_PREFIX = Regex("^#{1,6}\\s+")
private val BULLET = Regex("^(\\s*)[-*+]\\s+")
private val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)]\\(([^)]+)\\)|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])")

private fun AnnotatedString.Builder.appendInline(text: String, accent: Color, codeBackground: Color) {
    var cursor = 0
    for (match in INLINE.findAll(text)) {
        append(text.substring(cursor, match.range.first))
        val (bold, code, linkLabel, _, italic) = match.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(bold, accent, codeBackground) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, background = codeBackground)) {
                append(" $code ")
            }
            linkLabel.isNotEmpty() -> withStyle(SpanStyle(color = accent)) { append(linkLabel) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(italic) }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

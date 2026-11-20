package com.deepseek.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.mobile.ui.theme.LocalDsPalette

sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class CodeFence(val language: String, val code: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class NumberedList(val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.replace("\r\n", "\n").split('\n')
    var index = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            blocks.add(MdBlock.Paragraph(paragraph.toString().trim('\n')))
            paragraph.setLength(0)
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val language = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                index++
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    code.appendLine(lines[index])
                    index++
                }
                index++
                blocks.add(MdBlock.CodeFence(language, code.toString().trimEnd('\n')))
                continue
            }
            trimmed == "---" || trimmed == "***" -> {
                flushParagraph()
                blocks.add(MdBlock.Rule)
            }
            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length
                if (level in 1..6 && trimmed.length > level && trimmed[level] == ' ') {
                    flushParagraph()
                    blocks.add(MdBlock.Heading(level, trimmed.substring(level + 1).trim()))
                } else {
                    paragraph.appendLine(line)
                }
            }
            trimmed.startsWith("> ") || trimmed == ">" -> {
                flushParagraph()
                val quote = StringBuilder()
                while (index < lines.size && lines[index].trim().let { it.startsWith("> ") || it == ">" }) {
                    quote.appendLine(lines[index].trim().removePrefix(">").trim())
                    index++
                }
                blocks.add(MdBlock.Quote(quote.toString().trim()))
                continue
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (index < lines.size) {
                    val current = lines[index].trim()
                    if (current.startsWith("- ") || current.startsWith("* ") || current.startsWith("+ ")) {
                        items.add(current.substring(2).trim())
                        index++
                    } else if (current.isNotEmpty() && !current.startsWith("#") && lines[index].startsWith("  ")) {
                        items[items.lastIndex] = items.last() + " " + current
                        index++
                    } else {
                        break
                    }
                }
                blocks.add(MdBlock.BulletList(items))
                continue
            }
            Regex("^\\d+\\.\\s").containsMatchIn(trimmed) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (index < lines.size && Regex("^\\d+\\.\\s").containsMatchIn(lines[index].trim())) {
                    items.add(lines[index].trim().replaceFirst(Regex("^\\d+\\.\\s"), ""))
                    index++
                }
                blocks.add(MdBlock.NumberedList(items))
                continue
            }
            trimmed.isEmpty() -> flushParagraph()
            else -> paragraph.appendLine(line)
        }
        index++
    }
    flushParagraph()
    return blocks
}

fun inlineMarkdown(text: String, linkColor: Color, codeBackground: Color): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) {
                append(plain.toString())
                plain.setLength(0)
            }
        }

        while (i < text.length) {
            val rest = text.substring(i)
            when {
                rest.startsWith("**") -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        flush()
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        plain.append(text[i]); i++
                    }
                }
                rest.startsWith("__") -> {
                    val end = text.indexOf("__", i + 2)
                    if (end > i) {
                        flush()
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        plain.append(text[i]); i++
                    }
                }
                rest.startsWith("`") -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBackground,
                                fontSize = 14.sp,
                            ),
                        ) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        plain.append(text[i]); i++
                    }
                }
                rest.startsWith("~~") -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i) {
                        flush()
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        plain.append(text[i]); i++
                    }
                }
                rest.startsWith("[") -> {
                    val labelEnd = text.indexOf("](", i + 1)
                    val urlEnd = if (labelEnd > i) text.indexOf(')', labelEnd + 2) else -1
                    if (labelEnd > i && urlEnd > labelEnd) {
                        flush()
                        val label = text.substring(i + 1, labelEnd)
                        val url = text.substring(labelEnd + 2, urlEnd)
                        withLink(
                            LinkAnnotation.Url(
                                url,
                                TextLinkStyles(
                                    SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                            ),
                        ) {
                            append(label)
                        }
                        i = urlEnd + 1
                    } else {
                        plain.append(text[i]); i++
                    }
                }
                rest.startsWith("*") || rest.startsWith("_") -> {
                    val marker = text[i]
                    val end = text.indexOf(marker, i + 1)
                    if (end > i + 1) {
                        flush()
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        plain.append(text[i]); i++
                    }
                }
                else -> {
                    plain.append(text[i]); i++
                }
            }
        }
        flush()
    }
}

@Composable
fun MarkdownText(source: String, modifier: Modifier = Modifier) {
    val palette = LocalDsPalette.current
    val blocks = remember(source) { parseMarkdown(source) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text, palette.brand, palette.surfaceMuted),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 31.sp, letterSpacing = 0.2.sp),
                    color = palette.textPrimary,
                )
                is MdBlock.Heading -> Text(
                    text = inlineMarkdown(block.text, palette.brand, palette.surfaceMuted),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                is MdBlock.CodeFence -> CodeBlock(block.language, block.code)
                is MdBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    block.items.forEach { item ->
                        Row {
                            Text(
                                "•",
                                color = palette.textTertiary,
                                modifier = Modifier.width(14.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = inlineMarkdown(item, palette.brand, palette.surfaceMuted),
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 31.sp, letterSpacing = 0.2.sp),
                                color = palette.textPrimary,
                            )
                        }
                    }
                }
                is MdBlock.NumberedList -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    block.items.forEachIndexed { position, item ->
                        Row {
                            Text(
                                "${position + 1}.",
                                color = palette.textTertiary,
                                modifier = Modifier.width(20.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = inlineMarkdown(item, palette.brand, palette.surfaceMuted),
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 31.sp, letterSpacing = 0.2.sp),
                                color = palette.textPrimary,
                            )
                        }
                    }
                }
                is MdBlock.Quote -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.surfaceMuted)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = inlineMarkdown(block.text, palette.brand, palette.surface),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = palette.textSecondary,
                    )
                }
                MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(0.5.dp)
                        .background(palette.border),
                )
            }
        }
    }
}

@Composable
fun CodeBlock(language: String, code: String) {
    val palette = LocalDsPalette.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val highlighted = remember(code, language, palette) {
        highlightCode(code, language, palette.codeKeyword, palette.codeString, palette.codeNumber, palette.codeComment)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.codeBackground)
            .border(0.5.dp, palette.border, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language.ifBlank { "text" },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = palette.textTertiary,
            )
            Text(
                text = if (copied) "已复制" else "复制",
                style = MaterialTheme.typography.labelSmall,
                color = if (copied) palette.success else palette.textTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(palette.border),
        )
        Text(
            text = highlighted,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.8.sp,
                lineHeight = 19.sp,
            ),
            color = palette.textPrimary,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/**
 * Lightweight tokenizer used for fenced code: comments, strings, numbers,
 * keywords and call names get colour; everything else keeps the body colour.
 * It is intentionally small and language-agnostic rather than a full parser.
 */
fun highlightCode(
    code: String,
    language: String,
    keywordColor: Color,
    stringColor: Color,
    numberColor: Color,
    commentColor: Color,
): AnnotatedString {
    val lang = language.lowercase()
    val hashComments = lang in setOf("py", "python", "sh", "bash", "zsh", "yaml", "yml", "toml", "rb", "ruby", "r", "makefile", "dockerfile", "ini", "conf")
    val slashComments = lang !in setOf("py", "python", "sh", "bash", "zsh", "yaml", "yml", "toml", "rb", "ruby", "r", "makefile", "dockerfile")
    val token = Regex(
        buildString {
            append("(/\\*[\\s\\S]*?\\*/)")
            if (slashComments) append("|(//[^\\n]*)")
            if (hashComments) append("|(#[^\\n]*)")
            append("|(\"(?:\\\\.|[^\"\\\\])*\")")
            append("|('(?:\\\\.|[^'\\\\])*')")
            append("|(`[^`]*`)")
            append("|(\\b\\d+(?:\\.\\d+)?\\b)")
            append("|(\\b[A-Za-z_][A-Za-z0-9_]*\\b)")
        },
    )
    val keywords = setOf(
        "val", "var", "fun", "class", "object", "interface", "data", "suspend", "override",
        "private", "public", "internal", "protected", "import", "package", "return",
        "if", "else", "for", "while", "when", "do", "try", "catch", "finally", "throw",
        "true", "false", "null", "const", "let", "new", "this", "super", "typealias",
        "def", "async", "await", "from", "as", "in", "is", "not", "and", "or", "None",
        "True", "False", "lambda", "pass", "raise", "except", "with", "yield", "global",
        "function", "export", "default", "extends", "implements", "static", "void",
        "int", "float", "double", "bool", "string", "char", "struct", "enum", "const",
        "switch", "case", "break", "continue", "typeof", "instanceof", "delete",
        "match", "elif", "print", "println", "console",
    )
    return buildAnnotatedString {
        var cursor = 0
        for (match in token.findAll(code)) {
            if (match.range.first > cursor) append(code.substring(cursor, match.range.first))
            val value = match.value
            val group = match.groups
            when {
                group[1] != null || group[2] != null || group[3] != null -> withStyle(
                    SpanStyle(color = commentColor, fontStyle = FontStyle.Italic),
                ) { append(value) }
                group[4] != null || group[5] != null || group[6] != null -> withStyle(
                    SpanStyle(color = stringColor),
                ) { append(value) }
                group[7] != null -> withStyle(SpanStyle(color = numberColor)) { append(value) }
                else -> {
                    val after = code.getOrNull(match.range.last + 1)
                    when {
                        value in keywords -> withStyle(
                            SpanStyle(color = keywordColor, fontWeight = FontWeight.Medium),
                        ) { append(value) }
                        after == '(' -> withStyle(SpanStyle(color = keywordColor.copy(alpha = 0.9f))) {
                            append(value)
                        }
                        else -> append(value)
                    }
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < code.length) append(code.substring(cursor))
    }
}

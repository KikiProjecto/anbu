package com.anbu.research.ui.components

import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anbu.research.ui.theme.*

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    isGenerating: Boolean = false
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Animated cursor for token streaming
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )

    // Simple markdown block parser
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        blocks.forEachIndexed { index, block ->
            val isLastBlock = index == blocks.lastIndex
            when (block) {
                is MarkdownBlock.Header -> {
                    Text(
                        text = buildAnnotatedString {
                            append(block.text)
                            if (isGenerating && isLastBlock) {
                                withStyle(SpanStyle(color = AnbuCyan.copy(alpha = cursorAlpha))) {
                                    append(" ▌")
                                }
                            }
                        },
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineMedium.copy(color = AnbuCyan, fontWeight = FontWeight.Bold)
                            2 -> MaterialTheme.typography.titleLarge.copy(color = AnbuVioletLight, fontWeight = FontWeight.Bold)
                            else -> MaterialTheme.typography.titleMedium.copy(color = AnbuAmber, fontWeight = FontWeight.SemiBold)
                        },
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                is MarkdownBlock.CodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AnbuCodeBg)
                            .border(1.dp, AnbuCodeBorder, RoundedCornerShape(10.dp))
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = block.language.ifBlank { "code" }.uppercase(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = AnbuCyan
                                    )
                                )
                                Box(
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(AnbuSurfaceElevated)
                                        .clickable(role = Role.Button, onClickLabel = "Copy code block") {
                                            clipboardManager.setText(AnnotatedString(block.code))
                                            Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Copy",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            color = AnbuTextSecondary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = buildAnnotatedString {
                                    append(block.code)
                                    if (isGenerating && isLastBlock) {
                                        withStyle(SpanStyle(color = AnbuCyan.copy(alpha = cursorAlpha))) {
                                            append(" ▌")
                                        }
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    color = AnbuTextPrimary
                                )
                            )
                        }
                    }
                }
                is MarkdownBlock.ListItem -> {
                    Row(
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "• ",
                            style = MaterialTheme.typography.bodyLarge.copy(color = AnbuCyan, fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = buildAnnotatedString {
                                appendFormattedInline(block.text)
                                if (isGenerating && isLastBlock) {
                                    withStyle(SpanStyle(color = AnbuCyan.copy(alpha = cursorAlpha))) {
                                        append(" ▌")
                                    }
                                }
                            },
                            style = MaterialTheme.typography.bodyLarge.copy(color = AnbuTextPrimary)
                        )
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = buildAnnotatedString {
                            appendFormattedInline(block.text)
                            if (isGenerating && isLastBlock) {
                                withStyle(SpanStyle(color = AnbuCyan.copy(alpha = cursorAlpha))) {
                                    append(" ▌")
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(color = AnbuTextPrimary, lineHeight = 24.sp)
                    )
                }
            }
        }
        if (blocks.isEmpty() && isGenerating) {
            Text(
                text = "▌",
                style = MaterialTheme.typography.bodyLarge.copy(color = AnbuCyan.copy(alpha = cursorAlpha))
            )
        }
    }
}

private sealed class MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
    data class ListItem(val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
}

private fun parseMarkdownBlocks(content: String): List<MarkdownBlock> {
    if (content.isBlank()) return emptyList()
    val lines = content.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var inCode = false
    var codeLang = ""
    val codeBuffer = StringBuilder()

    for (line in lines) {
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                blocks.add(MarkdownBlock.CodeBlock(codeLang, codeBuffer.toString().trimEnd()))
                codeBuffer.clear()
                inCode = false
            } else {
                inCode = true
                codeLang = line.trimStart().removePrefix("```").trim()
            }
            continue
        }

        if (inCode) {
            codeBuffer.append(line).append("\n")
            continue
        }

        val trimmed = line.trim()
        when {
            trimmed.startsWith("# ") -> blocks.add(MarkdownBlock.Header(1, trimmed.substring(2)))
            trimmed.startsWith("## ") -> blocks.add(MarkdownBlock.Header(2, trimmed.substring(3)))
            trimmed.startsWith("### ") -> blocks.add(MarkdownBlock.Header(3, trimmed.substring(4)))
            trimmed.startsWith("- ") -> blocks.add(MarkdownBlock.ListItem(trimmed.substring(2)))
            trimmed.startsWith("* ") -> blocks.add(MarkdownBlock.ListItem(trimmed.substring(2)))
            trimmed.isNotBlank() -> blocks.add(MarkdownBlock.Paragraph(line))
        }
    }

    if (inCode && codeBuffer.isNotEmpty()) {
        blocks.add(MarkdownBlock.CodeBlock(codeLang, codeBuffer.toString().trimEnd()))
    }

    return blocks
}

private fun AnnotatedString.Builder.appendFormattedInline(text: String) {
    var index = 0
    val length = text.length
    var isBold = false
    var isItalic = false
    var isCode = false
    val currentWord = StringBuilder()

    while (index < length) {
        when {
            text.startsWith("**", index) -> {
                if (currentWord.isNotEmpty()) {
                    appendStyledText(currentWord.toString(), isBold, isItalic, isCode)
                    currentWord.clear()
                }
                isBold = !isBold
                index += 2
            }
            text.startsWith("*", index) -> {
                if (currentWord.isNotEmpty()) {
                    appendStyledText(currentWord.toString(), isBold, isItalic, isCode)
                    currentWord.clear()
                }
                isItalic = !isItalic
                index += 1
            }
            text.startsWith("`", index) -> {
                if (currentWord.isNotEmpty()) {
                    appendStyledText(currentWord.toString(), isBold, isItalic, isCode)
                    currentWord.clear()
                }
                isCode = !isCode
                index += 1
            }
            else -> {
                currentWord.append(text[index])
                index++
            }
        }
    }
    if (currentWord.isNotEmpty()) {
        appendStyledText(currentWord.toString(), isBold, isItalic, isCode)
    }
}

private fun AnnotatedString.Builder.appendStyledText(
    str: String,
    isBold: Boolean,
    isItalic: Boolean,
    isCode: Boolean
) {
    if (isCode) {
        withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = AnbuSurfaceElevated,
                color = AnbuCyan,
                fontSize = 14.sp
            )
        ) {
            append(" $str ")
        }
    } else {
        withStyle(
            SpanStyle(
                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal
            )
        ) {
            append(str)
        }
    }
}

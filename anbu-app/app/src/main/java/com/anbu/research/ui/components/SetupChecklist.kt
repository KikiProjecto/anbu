package com.anbu.research.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anbu.research.core.ModelPaths
import com.anbu.research.ui.theme.*

@Composable
fun SetupChecklist(
    missingFiles: List<String>,
    errorMessage: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val (modelPath) = remember { ModelPaths.resolve(context) }
    val dirPath = modelPath.substringBeforeLast('/')

    val isModelMissing = missingFiles.contains("model.gguf")
    val isDbMissing = missingFiles.contains("kb.db")
    val isIndexMissing = missingFiles.contains("kb.usearch")

    val adbCommand = buildString {
        append("adb shell mkdir -p ").append(dirPath).append("\n")
        if (isModelMissing) append("adb push model.gguf ").append(dirPath).append("/\n")
        if (isDbMissing) append("adb push kb.db ").append(dirPath).append("/\n")
        if (isIndexMissing) append("adb push kb.usearch ").append(dirPath).append("/")
    }.trim()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AnbuSurfaceDark)
            .border(1.dp, AnbuBorder, RoundedCornerShape(16.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "First-Run Setup Checklist",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = AnbuAmber
                )
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AnbuCrimson.copy(alpha = 0.2f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "ACTION REQUIRED",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = AnbuCrimson,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }

        Text(
            text = "ANBU requires offline assets in app storage:",
            style = MaterialTheme.typography.bodyMedium.copy(color = AnbuTextSecondary)
        )

        // File Checklist items
        FileStatusItem(name = "model.gguf (~25GB MoE Weights)", missing = isModelMissing)
        FileStatusItem(name = "kb.db (SQLite FTS5 Corpus)", missing = isDbMissing)
        FileStatusItem(name = "kb.usearch (Vector HNSW Index)", missing = isIndexMissing)

        if (errorMessage != null && missingFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AnbuCrimson.copy(alpha = 0.1f))
                    .border(1.dp, AnbuCrimson.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = "Engine error: $errorMessage",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = AnbuCrimson
                    )
                )
            }
        }

        if (missingFiles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Target directory:\n$dirPath",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = AnbuTextMuted
                )
            )

            // ADB Push Code block
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AnbuCodeBg)
                    .border(1.dp, AnbuCodeBorder, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ADB Push Commands",
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
                                .clickable(role = Role.Button, onClickLabel = "Copy ADB commands") {
                                    clipboardManager.setText(AnnotatedString(adbCommand))
                                    Toast.makeText(context, "ADB commands copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Copy ADB Command",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = AnbuTextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = adbCommand,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = AnbuTextPrimary,
                            fontSize = 11.sp
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        GlowingButton(
            text = "Re-check Assets & Retry",
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            variant = ButtonVariant.PRIMARY
        )
    }
}

@Composable
private fun FileStatusItem(name: String, missing: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (missing) AnbuCrimson.copy(alpha = 0.1f) else AnbuEmerald.copy(alpha = 0.1f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = if (missing) "✕" else "✓",
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (missing) AnbuCrimson else AnbuEmerald
            )
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = if (missing) AnbuTextPrimary else AnbuTextSecondary
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (missing) "MISSING" else "FOUND",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (missing) AnbuCrimson else AnbuEmerald
            )
        )
    }
}

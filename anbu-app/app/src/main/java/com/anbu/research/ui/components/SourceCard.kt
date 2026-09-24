package com.anbu.research.ui.components

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anbu.research.core.RAGHit
import com.anbu.research.ui.theme.*

@Composable
fun SourceCard(
    hit: RAGHit,
    index: Int,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // No "NN% match" pill: hit.score is the RRF fusion score (sum of 1/(60+rank),
    // anbu_rag.cpp:321) — it tops out around 0.03 and is not a similarity, so any
    // percentage rendered from it would be invented. Rank order is the real signal.

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, if (expanded) AnbuCyan.copy(alpha = 0.6f) else AnbuBorder, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse source" else "Expand source") {
                expanded = !expanded
            }
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = AnbuSurfaceDark)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Citation Rank Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AnbuVioletDark)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "#$index",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AnbuCyan
                            )
                        )
                    }

                    // Title
                    Text(
                        text = hit.title.ifBlank { "Untitled Document" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = AnbuTextPrimary
                        ),
                        maxLines = if (expanded) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

            }

            if (hit.source.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = hit.source,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = AnbuTextMuted
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Snippet body
            Text(
                text = hit.snippet,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = AnbuTextSecondary,
                    lineHeight = 18.sp
                ),
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )

            // Footer controls when expanded
            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tap to collapse",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = AnbuTextMuted,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    )
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(AnbuSurfaceElevated)
                            .clickable(role = Role.Button, onClickLabel = "Copy snippet") {
                                clipboardManager.setText(AnnotatedString(hit.snippet))
                                Toast.makeText(context, "Snippet copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Copy snippet",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = AnbuCyan,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }
}

package com.anbu.research.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anbu.research.ui.components.AnbuHeader
import com.anbu.research.ui.components.GlowingButton
import com.anbu.research.ui.components.PromptChip
import com.anbu.research.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(onSearch: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val samplePrompts = remember {
        listOf(
            "Security model of GrapheneOS & Android sandboxing",
            "How Mixture of Experts (MoE) routing works in LLMs",
            "USearch vs Faiss vector index benchmarks",
            "Quantum error correction fundamentals",
            "SQLite FTS5 full-text search indexing"
        )
    }

    val submitQuery = {
        if (query.isNotBlank()) {
            focusManager.clearFocus()
            onSearch(query.trim())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AnbuBgDark)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnbuHeader()

            Spacer(modifier = Modifier.height(32.dp))

            // Query Input Box
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            "Apa yang ingin Anda teliti hari ini?",
                            style = MaterialTheme.typography.bodyMedium.copy(color = AnbuTextSecondary)
                        )
                    },
                    placeholder = {
                        Text(
                            "Ketik topik riset atau pertanyaan ilmiah...",
                            style = MaterialTheme.typography.bodyMedium.copy(color = AnbuTextMuted)
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = AnbuSurfaceDark,
                        unfocusedContainerColor = AnbuSurfaceDark,
                        focusedBorderColor = AnbuCyan,
                        unfocusedBorderColor = AnbuBorder,
                        focusedLabelColor = AnbuCyan,
                        cursorColor = AnbuCyan,
                        focusedTextColor = AnbuTextPrimary,
                        unfocusedTextColor = AnbuTextPrimary
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitQuery() }),
                    singleLine = false,
                    maxLines = 4,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear query",
                                    tint = AnbuTextMuted
                                )
                            }
                        }
                    }
                )

                GlowingButton(
                    text = "Research Offline",
                    onClick = submitQuery,
                    enabled = query.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Quick Prompt Suggestions Section
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "SUGGESTED RESEARCH TOPICS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = AnbuAmber,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    samplePrompts.forEach { prompt ->
                        PromptChip(
                            text = prompt,
                            onClick = {
                                query = prompt
                                submitQuery()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Footer info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(AnbuEmerald)
                )
                Text(
                    text = "Engine: Ready • Context: 4096 tokens • Hybrid Search (FTS5 + HNSW)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = AnbuTextMuted,
                        fontSize = 11.sp
                    )
                )
            }
        }
    }
}

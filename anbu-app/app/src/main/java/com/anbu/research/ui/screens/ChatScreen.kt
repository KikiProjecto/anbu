package com.anbu.research.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anbu.research.ui.AIEngineViewModel
import com.anbu.research.ui.components.*
import com.anbu.research.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(query: String, onBack: () -> Unit, vm: AIEngineViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()

    val status by vm.chatStatus.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val answer by vm.answer.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    LaunchedEffect(query) {
        vm.ask(query)
    }

    BackHandler {
        vm.stop()
        onBack()
    }

    val busy = status == AIEngineViewModel.ChatStatus.SEARCHING ||
            status == AIEngineViewModel.ChatStatus.GENERATING

    // Keep screen on during inference
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    // Follow the stream, but only while the user is already parked at the bottom —
    // scrolling up to re-read an earlier source must not be yanked back every token.
    // Targets the *end* of the last item, not its top: the answer item is taller
    // than the viewport, so scrollToItem(index) would just re-align its headline.
    LaunchedEffect(answer, status) {
        if (answer.isEmpty() && !busy) return@LaunchedEffect
        if (listState.canScrollForward) return@LaunchedEffect
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        val overflow = (last.offset + last.size) - listState.layoutInfo.viewportEndOffset
        if (overflow > 0) listState.scrollBy(overflow.toFloat())
    }

    Scaffold(
        containerColor = AnbuBgDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "NEURAL RESEARCH",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = AnbuCyan,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = query,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = AnbuTextPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.stop()
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to search",
                            tint = AnbuCyan
                        )
                    }
                },
                actions = {
                    if (busy) {
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(AnbuCrimson.copy(alpha = 0.2f))
                                .border(1.dp, AnbuCrimson.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button, onClickLabel = "Stop generation") { vm.stop() }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "■ Stop",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = AnbuCrimson,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AnbuBgDark,
                    titleContentColor = AnbuTextPrimary
                )
            )
        },
        bottomBar = {
            if (answer.isNotEmpty() && !busy) {
                Surface(
                    color = AnbuSurfaceDark,
                    tonalElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, AnbuBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Copy Answer Button
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AnbuSurfaceElevated)
                                    .border(1.dp, AnbuBorder, RoundedCornerShape(10.dp))
                                    .clickable(role = Role.Button) {
                                        clipboardManager.setText(AnnotatedString(answer))
                                        Toast.makeText(context, "Full answer copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Copy Answer", style = MaterialTheme.typography.labelSmall.copy(color = AnbuTextPrimary))
                            }

                            // Share Button
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AnbuSurfaceElevated)
                                    .border(1.dp, AnbuBorder, RoundedCornerShape(10.dp))
                                    .clickable(role = Role.Button) {
                                        val sendIntent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, "Research Question: $query\n\nAnswer:\n$answer")
                                            type = "text/plain"
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "Share Research"))
                                    }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Share", style = MaterialTheme.typography.labelSmall.copy(color = AnbuTextPrimary))
                            }
                        }

                        // New Search Button
                        Box(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AnbuCyan.copy(alpha = 0.15f))
                                .border(1.dp, AnbuCyan.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button) {
                                    vm.resetChat()
                                    onBack()
                                }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("New Query", style = MaterialTheme.typography.labelSmall.copy(color = AnbuCyan, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Item 1: Multi-stage status indicator
            item(key = "status") {
                StatusIndicator(status = status)
            }

            // Item 2: Retrieved RAG context sources
            if (sources.isNotEmpty()) {
                item(key = "sources-header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "RETRIEVED KNOWLEDGE SOURCES",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = AnbuAmber,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(AnbuAmber.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${sources.size} hits",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = AnbuAmber
                                )
                            )
                        }
                    }
                }

                itemsIndexed(sources, key = { _, hit -> hit.id }) { index, hit ->
                    SourceCard(hit = hit, index = index + 1)
                }
            }

            // Item 3: Answer area with Markdown & streaming cursor
            item(key = "answer") {
                if (answer.isNotEmpty() || status == AIEngineViewModel.ChatStatus.GENERATING) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(AnbuSurfaceDark)
                            .border(1.dp, AnbuBorder, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SYNTHESIZED RESPONSE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = AnbuCyan,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            )
                            if (busy) {
                                Text(
                                    text = "STREAMING TOKENS...",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = AnbuVioletLight
                                    )
                                )
                            }
                        }

                        HorizontalDivider(color = AnbuBorder, thickness = 1.dp)

                        MarkdownText(
                            markdown = answer,
                            isGenerating = status == AIEngineViewModel.ChatStatus.GENERATING
                        )
                    }
                }
            }

            // Item 4: Error message
            if (error != null) {
                item(key = "error") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AnbuCrimson.copy(alpha = 0.1f))
                            .border(1.dp, AnbuCrimson.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "Error: $error",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                color = AnbuCrimson
                            )
                        )
                    }
                }
            }
        }
    }
}

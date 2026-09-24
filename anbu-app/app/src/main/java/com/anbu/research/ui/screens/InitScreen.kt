package com.anbu.research.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anbu.research.core.AIEngineManager
import com.anbu.research.core.ModelPaths
import com.anbu.research.ui.AIEngineViewModel
import com.anbu.research.ui.components.AnbuHeader
import com.anbu.research.ui.components.SetupChecklist
import com.anbu.research.ui.theme.*

@Composable
fun InitScreen(onReady: () -> Unit, vm: AIEngineViewModel) {
    val context = LocalContext.current
    val phase by vm.initPhase.collectAsStateWithLifecycle()
    val error by vm.initError.collectAsStateWithLifecycle()

    val paths = remember { ModelPaths.resolve(context) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        if (phase == AIEngineManager.Phase.UNINITIALIZED ||
            phase == AIEngineManager.Phase.FAILED
        ) {
            vm.initialize(paths.first, paths.second, paths.third)
        }
    }

    LaunchedEffect(phase) {
        if (phase == AIEngineManager.Phase.READY) onReady()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AnbuBgDark)
            .statusBarsPadding()
            .navigationBarsPadding(),
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

            when (phase) {
                AIEngineManager.Phase.UNINITIALIZED, AIEngineManager.Phase.LOADING -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(32.dp))
                                .background(AnbuSurfaceElevated)
                                .border(1.dp, AnbuCyanGlow, RoundedCornerShape(32.dp))
                        ) {
                            CircularProgressIndicator(
                                color = AnbuCyan,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Text(
                            text = "Loading MoE Weights to Memory...",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = AnbuCyan
                            )
                        )

                        Text(
                            text = "Paging ~25GB GGUF parameters via mmap() • Zero internet required",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = AnbuTextMuted
                            )
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // System Parameters card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(AnbuSurfaceDark)
                                .border(1.dp, AnbuBorder, RoundedCornerShape(14.dp))
                                .padding(16.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "ENGINE HARDWARE ALLOCATION",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = AnbuAmber,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                InfoRow(label = "RAM Envelope", value = "Max 12 GB (9 GB App)")
                                InfoRow(label = "Model Strategy", value = "Mixtral 8x7B / Qwen MoE (Q3/Q4)")
                                InfoRow(label = "Storage Limit", value = "Max 50 GB NVMe")
                                InfoRow(label = "Inference Architecture", value = "C++ NDK + ARM NEON")
                            }
                        }
                    }
                }
                AIEngineManager.Phase.READY -> {
                    Text(
                        text = "Engine Ready. Redirecting...",
                        style = MaterialTheme.typography.titleLarge.copy(color = AnbuEmerald)
                    )
                }
                AIEngineManager.Phase.FAILED -> {
                    val missing = remember(attempt) { ModelPaths.missing(context) }
                    SetupChecklist(
                        missingFiles = missing,
                        errorMessage = error,
                        onRetry = { attempt++ }
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(color = AnbuTextSecondary)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = AnbuTextPrimary
            )
        )
    }
}

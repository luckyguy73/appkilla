package com.github.luckyguy73.appkilla.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.github.luckyguy73.appkilla.ui.components.AdbStatusBanner
import com.github.luckyguy73.appkilla.ui.components.AppGridItemCard
import com.github.luckyguy73.appkilla.ui.components.StatusHeaderCard
import com.github.luckyguy73.appkilla.ui.theme.CyanGlow
import com.github.luckyguy73.appkilla.ui.theme.DarkBackground
import com.github.luckyguy73.appkilla.ui.theme.DarkBackgroundSecondary
import com.github.luckyguy73.appkilla.ui.theme.TextSecondary
import com.github.luckyguy73.appkilla.ui.viewmodel.MainViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppKillaScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            DarkBackground,
            DarkBackgroundSecondary,
            Color(0xFF090D16)
        )
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 1. Top System Status Header Card
            StatusHeaderCard(
                activeAppsCount = uiState.activeBackgroundAppsCount,
                selectedAppsCount = uiState.selectedAppsCount,
                totalMemoryFreedMb = uiState.totalMemoryFreedMb,
                isExecuting = uiState.isExecutingForceStop,
                onForceCloseClick = { viewModel.forceCloseSelectedApps() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 2. ADB Status / User Message Banner
            AdbStatusBanner(
                state = uiState.adbConnectionState,
                onReconnectClick = { viewModel.reconnectAdb() }
            )

            if (uiState.userMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x3300E5FF), RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = uiState.userMessage ?: "",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CyanGlow
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Grid Control Bar (Select All / Refresh)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RUNNING APPLICATIONS",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { viewModel.toggleSelectAll() },
                        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp))
                    ) {
                        val allSelected = uiState.apps.isNotEmpty() && uiState.apps.all { it.isSelected }
                        Text(
                            text = if (allSelected) "DESELECT ALL" else "SELECT ALL",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = { viewModel.loadApps() },
                        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp))
                    ) {
                        Text(
                            text = "REFRESH",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. App Cards Grid
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "spin")
                    val angle by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing)
                        ),
                        label = "angle"
                    )

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .rotate(angle)
                            .border(4.dp, CyanGlow, CircleShape)
                    )
                }
            } else if (uiState.apps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No active background apps found.",
                        fontSize = 18.sp,
                        color = TextSecondary
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(
                        items = uiState.apps,
                        key = { it.packageName }
                    ) { app ->
                        AppGridItemCard(
                            app = app,
                            onToggleSelect = { viewModel.toggleAppSelection(app.packageName) }
                        )
                    }
                }
            }
        }
    }
}

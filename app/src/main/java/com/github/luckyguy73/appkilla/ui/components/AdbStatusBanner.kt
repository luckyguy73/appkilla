package com.github.luckyguy73.appkilla.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.github.luckyguy73.appkilla.adb.AdbConnectionState
import com.github.luckyguy73.appkilla.ui.theme.CyanGlow
import com.github.luckyguy73.appkilla.ui.theme.ErrorRed
import com.github.luckyguy73.appkilla.ui.theme.SuccessGreen
import com.github.luckyguy73.appkilla.ui.theme.TextPrimary
import com.github.luckyguy73.appkilla.ui.theme.TextSecondary
import com.github.luckyguy73.appkilla.ui.theme.WarningOrange

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AdbStatusBanner(
    state: AdbConnectionState,
    onReconnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state is AdbConnectionState.Connected) return // Connected - no banner needed

    val (bgTint, borderTint, statusTitle, statusDesc) = when (state) {
        AdbConnectionState.Unauthorized -> Quadruple(
            Color(0x33F59E0B),
            WarningOrange,
            "ADB AUTHORIZATION REQUIRED",
            "Ensure ADB Debugging is enabled in Fire TV Settings, accept 'Always allow' on the system dialog, then click Reconnect."
        )
        AdbConnectionState.Connecting -> Quadruple(
            Color(0x3300E5FF),
            CyanGlow,
            "CONNECTING TO LOCAL ADB...",
            "Establishing local socket connection on 127.0.0.1:5555..."
        )
        is AdbConnectionState.Error -> Quadruple(
            Color(0x33EF4444),
            ErrorRed,
            "ADB CONNECTION ERROR",
            state.message
        )
        AdbConnectionState.Disconnected -> Quadruple(
            Color(0x3364748B),
            Color(0xFF64748B),
            "ADB DISCONNECTED",
            "Local ADB socket is currently offline."
        )
        AdbConnectionState.Connected -> Quadruple(
            Color(0x3310B981),
            SuccessGreen,
            "ADB CONNECTED",
            "Local socket connected."
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgTint)
            .border(1.dp, borderTint, RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = borderTint,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = statusDesc,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Button(
                onClick = onReconnectClick,
                shape = androidx.tv.material3.ButtonDefaults.shape(RoundedCornerShape(10.dp))
            ) {
                Text(
                    text = "RECONNECT",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

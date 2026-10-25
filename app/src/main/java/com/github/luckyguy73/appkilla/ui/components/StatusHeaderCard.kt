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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.luckyguy73.appkilla.ui.theme.CardBackground
import com.github.luckyguy73.appkilla.ui.theme.CyanGlow
import com.github.luckyguy73.appkilla.ui.theme.ElectricBlue
import com.github.luckyguy73.appkilla.ui.theme.ElectricBlueDark
import com.github.luckyguy73.appkilla.ui.theme.TextPrimary
import com.github.luckyguy73.appkilla.ui.theme.TextSecondary
import java.util.Locale

private val LightningBoltIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LightningBolt",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = PathParser().parsePathString("M11 21L13 13H7L13 3L11 11H17L11 21Z").toNodes(),
        fill = SolidColor(CyanGlow)
    ).build()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StatusHeaderCard(
    activeAppsCount: Int,
    selectedAppsCount: Int,
    totalMemoryFreedMb: Long,
    isExecuting: Boolean,
    onForceCloseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardBackground)
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(20.dp))
            .padding(horizontal = 28.dp, vertical = 22.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Status Text Block
            Column {
                Text(
                    text = "SYSTEM STATUS:",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    letterSpacing = 1.2.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "$activeAppsCount BACKGROUND APPS ACTIVE",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(4.dp))

                val freedFormatted = formatMemoryMb(totalMemoryFreedMb)
                Text(
                    text = "$freedFormatted MEMORY FREED TODAY",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary
                )
            }

            // Right Action Button
            ForceCloseButton(
                selectedCount = selectedAppsCount,
                isExecuting = isExecuting,
                onClick = onForceCloseClick
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ForceCloseButton(
    selectedCount: Int,
    isExecuting: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        enabled = !isExecuting,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused }
            .semantics { role = Role.Button }
    ) {
        val borderModifier = if (isFocused) {
            Modifier.border(2.5.dp, CyanGlow, RoundedCornerShape(16.dp))
        } else {
            Modifier.border(1.dp, Color(0x6600E5FF), RoundedCornerShape(16.dp))
        }

        val backgroundGradient = Brush.horizontalGradient(
            colors = listOf(ElectricBlue, ElectricBlueDark)
        )

        Box(
            modifier = Modifier
                .shadow(elevation = if (isFocused) 16.dp else 6.dp, shape = RoundedCornerShape(16.dp), spotColor = CyanGlow)
                .background(backgroundGradient, RoundedCornerShape(16.dp))
                .then(borderModifier)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isExecuting) "CLOSING APPS..." else "FORCE CLOSE",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = "($selectedCount SELECTED)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE0F2FE)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Icon(
                    imageVector = LightningBoltIcon,
                    contentDescription = "Force close lightning icon",
                    tint = CyanGlow,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

private fun formatMemoryMb(mb: Long): String {
    return if (mb >= 1024) {
        String.format(Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        "$mb MB"
    }
}

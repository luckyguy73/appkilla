package com.github.luckyguy73.appkilla.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.luckyguy73.appkilla.data.model.AppInfo
import com.github.luckyguy73.appkilla.ui.theme.CardBackground
import com.github.luckyguy73.appkilla.ui.theme.CyanGlow
import com.github.luckyguy73.appkilla.ui.theme.TextPrimary
import com.github.luckyguy73.appkilla.ui.theme.TextSecondary

private val DefaultAppIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "DefaultAppIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = PathParser().parsePathString("M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z").toNodes(),
        fill = SolidColor(CyanGlow)
    ).build()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppGridItemCard(
    app: AppInfo,
    onToggleSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val borderModifier = when {
        isFocused -> Modifier.border(3.dp, CyanGlow, RoundedCornerShape(18.dp))
        app.isSelected -> Modifier.border(1.5.dp, Color(0x9900E5FF), RoundedCornerShape(18.dp))
        else -> Modifier.border(1.dp, Color(0xFF334155), RoundedCornerShape(18.dp))
    }

    val cardBg = if (isFocused) Color(0xFF223147) else CardBackground

    Surface(
        onClick = onToggleSelect,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(
                    elevation = if (isFocused) 18.dp else 4.dp,
                    shape = RoundedCornerShape(18.dp),
                    spotColor = CyanGlow
                )
                .background(cardBg, RoundedCornerShape(18.dp))
                .then(borderModifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // App Icon Container
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0F172A)),
                    contentAlignment = Alignment.Center
                ) {
                    val appBitmap = remember(app.packageName, app.icon) {
                        try {
                            app.icon?.toBitmap()?.asImageBitmap()
                        } catch (_: Exception) {
                            null
                        }
                    }

                    if (appBitmap != null) {
                        Image(
                            bitmap = appBitmap,
                            contentDescription = "${app.appName} Icon",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(46.dp)
                        )
                    } else {
                        Icon(
                            imageVector = DefaultAppIcon,
                            contentDescription = "Default App Icon",
                            tint = CyanGlow,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // App Info Text Column
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = app.appName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = if (app.memoryUsageMb > 0) "${app.memoryUsageMb}MB" else "— MB",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                }

                // Selection checkmark dot / indicator
                if (app.isSelected) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(CyanGlow)
                    )
                }
            }
        }
    }
}

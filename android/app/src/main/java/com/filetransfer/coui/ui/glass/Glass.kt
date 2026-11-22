package com.filetransfer.coui.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

val LocalGlassBackdrop = compositionLocalOf<Backdrop?> { null }

@Composable
fun rememberMosendBackdrop(): LayerBackdrop = rememberLayerBackdrop()

/** 背景必须被 layerBackdrop 标记，玻璃层才能折射到它。 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, backdrop: LayerBackdrop) {
    val dark = isSystemInDarkTheme()
    Box(modifier = modifier.layerBackdrop(backdrop)) {
        val baseColors = if (dark) {
            listOf(Color(0xFF080B14), Color(0xFF15102B), Color(0xFF221035))
        } else {
            listOf(Color(0xFFE8F0FF), Color(0xFFF1E9FF), Color(0xFFFFEAF3))
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(baseColors, start = Offset(0f, 0f), end = Offset(1200f, 2400f)))
        )
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            fun blob(cx: Float, cy: Float, radius: Float, color: Color) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color, color.copy(alpha = 0f)),
                        center = Offset(cx, cy),
                        radius = radius,
                    ),
                    radius = radius,
                    center = Offset(cx, cy),
                )
            }
            if (dark) {
                blob(w * 0.15f, h * 0.12f, w * 0.65f, Color(0xFF2E62FF).copy(alpha = 0.32f))
                blob(w * 0.9f, h * 0.3f, w * 0.6f, Color(0xFF8A3CFF).copy(alpha = 0.26f))
                blob(w * 0.55f, h * 0.82f, w * 0.8f, Color(0xFFFF4D8D).copy(alpha = 0.16f))
            } else {
                blob(w * 0.1f, h * 0.1f, w * 0.75f, Color(0xFF5B8CFF).copy(alpha = 0.45f))
                blob(w * 0.95f, h * 0.28f, w * 0.65f, Color(0xFFA47BFF).copy(alpha = 0.38f))
                blob(w * 0.5f, h * 0.85f, w * 0.85f, Color(0xFFFF8FB8).copy(alpha = 0.30f))
            }
            // 细节纹理：斜线 + 圆环，帮助折射可见
            val lineColor = if (dark) Color.White.copy(alpha = 0.045f) else Color.White.copy(alpha = 0.35f)
            var x = -h
            while (x < w + h) {
                drawLine(lineColor, Offset(x, 0f), Offset(x + h, h), strokeWidth = 1.2f)
                x += 64f
            }
            drawCircle(
                color = Color.White.copy(alpha = if (dark) 0.05f else 0.28f),
                radius = w * 0.42f,
                center = Offset(w * 0.88f, h * 0.62f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
            )
            drawCircle(
                color = Color.White.copy(alpha = if (dark) 0.04f else 0.22f),
                radius = w * 0.28f,
                center = Offset(w * 0.08f, h * 0.72f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
            )
        }
        Text(
            text = "MOSEND",
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Black),
            color = if (dark) Color.White.copy(alpha = 0.035f) else Color(0xFF1A3A80).copy(alpha = 0.06f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp),
        )
    }
}

@Composable
fun glassFill(): Color {
    val dark = isSystemInDarkTheme()
    return if (dark) Color.White.copy(alpha = 0.075f) else Color.White.copy(alpha = 0.34f)
}

@Composable
fun glassBorder(): Color {
    val dark = isSystemInDarkTheme()
    return if (dark) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.72f)
}

@Composable
fun glassStrongFill(): Color {
    val dark = isSystemInDarkTheme()
    return if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.56f)
}

/**
 * 液态玻璃容器。effects=true 时使用 backdrop 的 vibrancy+blur+lens。
 * 列表项建议 effects=false（只保留半透明与描边），避免每行一条 RenderEffect。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    blurRadius: Dp = 10.dp,
    refractionHeight: Dp = 14.dp,
    refractionAmount: Dp = 30.dp,
    effects: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalGlassBackdrop.current
    val shape = RoundedCornerShape(corner)
    val base = if (effects && backdrop != null) {
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurRadius.toPx())
                lens(
                    refractionHeight = refractionHeight.toPx(),
                    refractionAmount = refractionAmount.toPx(),
                    chromaticAberration = true,
                )
            },
        )
    } else {
        modifier.background(glassFill(), shape)
    }
    Box(
        modifier = base
            .border(1.dp, glassBorder(), shape)
            .clip(shape)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun GlassTopBar(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        corner = 24.dp,
        blurRadius = 12.dp,
        refractionHeight = 12.dp,
        refractionAmount = 24.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (navigation != null) {
                navigation()
                Spacer(Modifier.width(6.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (actions != null) actions()
        }
    }
}

@Composable
fun GlassSegmentedTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        corner = 22.dp,
        refractionHeight = 10.dp,
        refractionAmount = 18.dp,
        contentPadding = PaddingValues(5.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                val active = index == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.88f)
                            else Color.Transparent
                        )
                        .clickable { onSelect(index) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissText: String? = null,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(0.92f),
            corner = 30.dp,
            blurRadius = 16.dp,
            refractionHeight = 16.dp,
            refractionAmount = 30.dp,
            contentPadding = PaddingValues(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                content()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (dismissText != null) {
                        TextButton(onClick = onDismissRequest) {
                            Text(dismissText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (confirmText != null && onConfirm != null) {
                        TextButton(onClick = onConfirm) {
                            Text(confirmText, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// 兼容旧调用：给内容直接留出玻璃内边距
@Composable
fun GlassContainer(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(modifier = modifier, contentPadding = PaddingValues(0.dp)) {
        Column(content = content)
    }
}

/** 轻量玻璃：半透明填充 + 高光描边，适合列表项（无 RenderEffect 开销）。 */
@Composable
fun Modifier.glassLite(corner: Dp = 24.dp): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .background(glassFill(), shape)
        .border(1.dp, glassBorder(), shape)
}

/** 玻璃气泡配色。 */
@Composable
fun glassIncomingBubble(): Color {
    val dark = isSystemInDarkTheme()
    return if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.62f)
}

@Composable
fun glassOutgoingBrush(): Brush {
    // 固定高饱和渐变，保证浅色/深色主题下白字对比度
    return Brush.linearGradient(
        listOf(
            Color(0xFF2F6BFF).copy(alpha = 0.96f),
            Color(0xFF7A45E6).copy(alpha = 0.94f),
        )
    )
}

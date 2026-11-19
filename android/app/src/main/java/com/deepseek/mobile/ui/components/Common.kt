package com.deepseek.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.mobile.R
import com.deepseek.mobile.ui.theme.LocalDsPalette

/** Cute whale mascot used by empty states, avatars and the about row. */
@Composable
fun WhaleMascot(size: Int = 64, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.whale_girl),
        contentDescription = null,
        modifier = modifier.size(size.dp),
    )
}

/**
 * Compact app bar. Detection of the official app: a circular back button on
 * the left and a centred title. Pass [centered] to get that layout.
 */
@Composable
fun DsTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color? = null,
    onBack: (() -> Unit)? = null,
    centered: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalDsPalette.current
    Column(modifier = modifier.fillMaxWidth().background(palette.surface)) {
        if (centered) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 10.dp),
            ) {
                if (onBack != null) {
                    CircleBackButton(onBack, Modifier.align(Alignment.CenterStart))
                }
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 52.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = subtitleColor ?: palette.textTertiary,
                            maxLines = 1,
                        )
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = if (onBack != null) 4.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    CircleBackButton(onBack)
                    Spacer(Modifier.width(4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = subtitleColor ?: palette.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(palette.border),
        )
    }
}

@Composable
fun CircleBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalDsPalette.current
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(palette.surfaceMuted)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowLeft,
            contentDescription = "返回",
            tint = palette.textPrimary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Capsule used for thinking / web-search switches and model chips. */
@Composable
fun DsPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    onMutedSurface: Boolean = false,
) {
    val palette = LocalDsPalette.current
    val background by animateColorAsState(
        when {
            selected -> palette.brandSoft
            onMutedSurface -> palette.surface
            else -> palette.surfaceMuted
        },
        label = "pillBackground",
    )
    val contentColor by animateColorAsState(
        if (selected) palette.brand else palette.textSecondary,
        label = "pillContent",
    )
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .border(
                0.5.dp,
                if (selected || !onMutedSurface) Color.Transparent else palette.border,
                RoundedCornerShape(999.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp),
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
fun StatusDot(color: Color, pulsing: Boolean, size: Int = 7) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "statusPulse")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "statusAlpha",
        )
        value
    } else {
        1f
    }
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

@Composable
fun DsEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    mascotSize: Int = 96,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = LocalDsPalette.current
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        WhaleMascot(size = mascotSize)
        Spacer(Modifier.height(18.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            ActionButton(text = actionLabel, onClick = onAction)
        }
    }
}

/**
 * Composer in the official app's shape: one rounded field whose bottom row
 * holds the capability switches, with attach and send circles outside it.
 */
@Composable
fun DsComposer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    busy: Boolean,
    onSendOrStop: () -> Unit,
    modifier: Modifier = Modifier,
    onAttach: (() -> Unit)? = null,
    above: (@Composable RowScope.() -> Unit)? = null,
    disclaimer: String? = null,
) {
    val palette = LocalDsPalette.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (above != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = above,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(palette.surfaceMuted)
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
            ) {
                Column {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 26.dp, max = 140.dp),
                        textStyle = LocalTextStyle.current.merge(
                            TextStyle(color = palette.textPrimary, fontSize = 16.sp, lineHeight = 24.sp),
                        ),
                        cursorBrush = SolidColor(palette.brand),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        keyboardActions = KeyboardActions(),
                        decorationBox = { inner ->
                            Box {
                                if (value.isEmpty()) {
                                    Text(
                                        placeholder,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                                        color = palette.textTertiary,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
            }
            if (onAttach != null) {
                Spacer(Modifier.width(8.dp))
                CircleActionButton(
                    icon = Icons.Filled.Add,
                    contentDescription = "添加图片",
                    filled = false,
                    onClick = onAttach,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (busy) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(palette.brand)
                        .clickable { onSendOrStop() },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White),
                    )
                }
            } else {
                val enabled = value.isNotBlank()
                CircleActionButton(
                    icon = Icons.Filled.Send,
                    contentDescription = "发送",
                    filled = true,
                    enabled = enabled,
                    onClick = onSendOrStop,
                )
            }
        }
        if (disclaimer != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                disclaimer,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textTertiary,
            )
        }
    }
}

@Composable
fun CircleActionButton(
    icon: ImageVector,
    contentDescription: String,
    filled: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val palette = LocalDsPalette.current
    val background = when {
        !filled -> palette.surface
        enabled -> palette.brand
        else -> palette.border
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(background)
            .border(
                0.5.dp,
                if (filled) Color.Transparent else palette.border,
                CircleShape,
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (filled) {
                if (enabled) Color.White else palette.textTertiary
            } else {
                palette.textPrimary
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val palette = LocalDsPalette.current
    Text(
        text,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = palette.textTertiary,
    )
}

@Composable
fun MessageActionRow(
    onCopy: () -> Unit,
    onRetry: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val palette = LocalDsPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "复制",
            style = MaterialTheme.typography.labelMedium,
            color = palette.textTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable { onCopy() }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
        if (onRetry != null) {
            Text(
                "重新生成",
                style = MaterialTheme.typography.labelMedium,
                color = palette.textTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onRetry() }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        if (extra != null) extra()
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    val palette = LocalDsPalette.current
    Box(modifier.fillMaxWidth().height(0.5.dp).background(palette.border))
}

@Composable
fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalDsPalette.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (filled) palette.brand else palette.surface)
            .border(
                0.5.dp,
                if (filled) Color.Transparent else palette.border,
                RoundedCornerShape(999.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) Color.White else palette.textSecondary,
        )
    }
}

@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val palette = LocalDsPalette.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(0.5.dp, palette.border, RoundedCornerShape(16.dp)),
        color = palette.surface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

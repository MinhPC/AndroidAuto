package com.minhphan.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Minimum touch target recommended for in-car UI. */
private val MinTouchTarget = 88.dp

/** Below this icon size the label switches to a smaller style so it still fits under the icon. */
private val CompactIcon = 56.dp

/**
 * An icon with its label under it, the shape of the dock's buttons. It dips slightly while
 * pressed, so a tap is felt even when a slow head unit takes a moment to react. [container] is an optional card
 * colour behind the whole tile, and [decoration] anything more drawn there (a gradient, a border), inside its corners.
 * Where there is no room for the label ([showLabel] false) it is only read out, for a screen reader.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TileLayout(
    label: String,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    container: Color = Color.Transparent,
    decoration: Modifier = Modifier,
    labelStyle: TextStyle? = null,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    showLabel: Boolean = true,
    verticalPadding: Dp = 10.dp,
    horizontalPadding: Dp = 6.dp,
    labelGap: Dp = 6.dp,
    iconOnlyPress: Boolean = false,
    icon: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(stiffness = 900f), label = "press")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .then(if (iconOnlyPress) Modifier else Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
            .clip(RoundedCornerShape(20.dp))
            .background(container)
            .background(if (iconOnlyPress && pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .then(decoration)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .heightIn(min = MinTouchTarget)
            .then(if (showLabel) Modifier else Modifier.semantics { contentDescription = label })
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) {
        Box(Modifier.size(iconSize).then(if (iconOnlyPress) Modifier.graphicsLayer { scaleX = scale; scaleY = scale } else Modifier),
            contentAlignment = Alignment.Center, content = icon)
        if (showLabel) Text(
            text = label,
            style = labelStyle ?: if (iconSize >= CompactIcon) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            color = labelColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = labelGap),
        )
    }
}

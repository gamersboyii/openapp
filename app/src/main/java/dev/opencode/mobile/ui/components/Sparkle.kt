package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimalist four-point "AI sparkle" — the assistant mark used across the
 * conversation canvas, the empty state and the drawer header. Drawn as a
 * vector so it renders crisp at any size and tints like any other icon.
 */
val OpenAiSparkle: ImageVector by lazy {
    ImageVector.Builder(
        name = "OpenAiSparkle",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color(0xFFECECF1)),
            pathFillType = PathFillType.NonZero,
        ) {
            // Concave four-point star: tips at (12,1) (23,12) (12,23) (1,12),
            // waist pulled toward the center so the arms read as glowing.
            moveTo(12f, 1f)
            curveTo(12.9f, 7.6f, 16.4f, 11.1f, 23f, 12f)
            curveTo(16.4f, 12.9f, 12.9f, 16.4f, 12f, 23f)
            curveTo(11.1f, 16.4f, 7.6f, 12.9f, 1f, 12f)
            curveTo(7.6f, 11.1f, 11.1f, 7.6f, 12f, 1f)
            close()
        }
    }.build()
}

/** Assistant avatar: the sparkle on the transparent canvas. */
@Composable
fun SparkleAvatar(size: Dp = 26.dp, modifier: Modifier = Modifier, tint: Color? = null) {
    Icon(
        imageVector = OpenAiSparkle,
        contentDescription = "opencode assistant",
        tint = tint ?: MaterialTheme.colorScheme.onBackground,
        modifier = modifier.size(size),
    )
}

/**
 * Small circular initial avatar shown next to the user's right-aligned
 * message, ChatGPT-style. [initial] defaults to the GitHub login's first
 * letter when one is connected, otherwise "U".
 */
@Composable
fun UserAvatar(initial: String, size: Dp = 26.dp, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
    ) {
        Text(
            text = initial,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

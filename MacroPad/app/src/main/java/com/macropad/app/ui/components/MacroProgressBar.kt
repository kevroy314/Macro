package com.macropad.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macropad.app.ui.theme.Red

@Composable
fun MacroProgressBar(
    label: String,
    current: Int,
    target: Int,
    color: Color,
    modifier: Modifier = Modifier,
    exceeded: Boolean = current > target
) {
    val progress by animateFloatAsState(
        targetValue = if (target > 0) (current.toFloat() / target).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(500),
        label = "progress"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (exceeded) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Exceeded",
                        tint = Red,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                text = "$current / $target",
                style = MaterialTheme.typography.bodyMedium,
                color = if (exceeded) Red else MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.2f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (exceeded) Red else color)
            )
        }
    }
}

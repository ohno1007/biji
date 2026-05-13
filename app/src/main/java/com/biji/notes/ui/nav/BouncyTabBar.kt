package com.biji.notes.ui.nav

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biji.notes.ui.glass.LiquidGlassState
import com.biji.notes.ui.glass.bouncyPress
import com.biji.notes.ui.glass.liquidGlass

data class TabItem(
    val key: String,
    val label: String,
    val icon: ImageVector
)

@Composable
fun BouncyTabBar(
    items: List<TabItem>,
    selected: String,
    onSelect: (String) -> Unit,
    glass: LiquidGlassState?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlass(
                    glass,
                    shape = RoundedCornerShape(50),
                    cornerRadius = 50.dp,
                    blurRadius = 40.dp,
                    tint = Color.White.copy(alpha = 0.10f)
                )
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val isSelected = item.key == selected
                TabSlot(
                    item = item,
                    selected = isSelected,
                    glass = glass,
                    onClick = { if (!isSelected) onSelect(item.key) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TabSlot(
    item: TabItem,
    selected: Boolean,
    glass: LiquidGlassState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.0f else 0.9f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "tabScale"
    )
    val lift by animateDpAsState(
        targetValue = if (selected) (-2).dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "tabLift"
    )

    Box(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .height(56.dp)
            .bouncyPress(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Row(
                modifier = Modifier
                    .offset(y = lift)
                    .scale(scale)
                    .liquidGlass(
                        glass,
                        shape = RoundedCornerShape(50),
                        cornerRadius = 50.dp,
                        blurRadius = 22.dp,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    item.icon,
                    contentDescription = item.label,
                    modifier = Modifier.size(18.dp),
                    tint = Color.White
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        } else {
            Icon(
                item.icon,
                contentDescription = item.label,
                modifier = Modifier.size(22.dp).scale(scale),
                tint = LocalContentColor.current.copy(alpha = 0.75f)
            )
        }
    }
}

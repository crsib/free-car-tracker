package me.crsib.freecartracker.ui.entries

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.crsib.freecartracker.ui.util.formatDate
import me.crsib.freecartracker.ui.util.formatMoney
import me.crsib.freecartracker.ui.util.icon

private fun StatIcon.vector(): ImageVector = when (this) {
    StatIcon.VOLUME -> Icons.Filled.WaterDrop
    StatIcon.ENERGY -> Icons.Filled.Bolt
    StatIcon.ODOMETER -> Icons.Filled.Straighten
    StatIcon.CONSUMPTION -> Icons.Filled.Speed
    StatIcon.LOCATION -> Icons.Filled.Place
}

private fun AmountBadge.vector(): ImageVector = when (this) {
    AmountBadge.TANK_FULL -> Icons.Filled.LocalGasStation
    AmountBadge.TANK_PARTIAL -> Icons.Outlined.LocalGasStation
    AmountBadge.BATTERY_FULL -> Icons.Filled.BatteryFull
    AmountBadge.BATTERY_HIGH -> Icons.Filled.Battery6Bar
    AmountBadge.BATTERY_MID -> Icons.Filled.Battery4Bar
    AmountBadge.BATTERY_LOW -> Icons.Filled.Battery2Bar
    AmountBadge.BATTERY_EMPTY -> Icons.Filled.Battery1Bar
    AmountBadge.BATTERY_UNKNOWN -> Icons.Filled.BatteryStd
}

private fun AmountBadge.description(): String = when (this) {
    AmountBadge.TANK_FULL -> "Full tank"
    AmountBadge.TANK_PARTIAL -> "Partial fill"
    AmountBadge.BATTERY_UNKNOWN -> "Charge"
    else -> "Battery level"
}

// Colour carries the level at a glance (the filled/outlined icon shapes are too subtle alone).
private val LevelGreen = Color(0xFF2E7D32)
private val LevelAmber = Color(0xFFF9A825)
private val LevelRed = Color(0xFFC62828)

@Composable
private fun AmountBadge.tint(): Color = when (this) {
    AmountBadge.TANK_FULL, AmountBadge.BATTERY_FULL, AmountBadge.BATTERY_HIGH -> LevelGreen
    AmountBadge.BATTERY_MID, AmountBadge.BATTERY_LOW -> LevelAmber
    AmountBadge.BATTERY_EMPTY -> LevelRed
    AmountBadge.TANK_PARTIAL, AmountBadge.BATTERY_UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun StatChip(stat: EntryStat) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            stat.icon.vector(),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stat.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One ledger entry as a tappable card. Used by History and by the dashboard's recent entries.
 * The overflow menu appears only when [onDelete] is supplied.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryCard(
    item: EntryCardItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    top = 10.dp,
                    bottom = 10.dp,
                    end = if (onDelete != null) 4.dp else 16.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The leading icon doubles as the fill-level cue for fuel/charge (full/partial tank,
            // battery level), colour-coded; expenses keep their category icon.
            val badge = item.amountBadge
            if (badge != null) {
                Icon(badge.vector(), contentDescription = badge.description(), tint = badge.tint())
            } else {
                Icon(item.kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.Medium)
                if (item.stats.isNotEmpty()) {
                    // Each stat is an atomic icon+value, so whole stats wrap to the next line
                    // instead of breaking a value mid-token (e.g. "2 kWh/" / "100km").
                    FlowRow(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item.stats.forEach { StatChip(it) }
                    }
                } else if (item.subtitle.isNotBlank()) {
                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall)
                }
                Text(formatDate(item.dateMillis), style = MaterialTheme.typography.bodySmall)
            }
            Text(formatMoney(item.amountMinor, item.currency), fontWeight = FontWeight.SemiBold)
            if (onDelete != null) {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onClick() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

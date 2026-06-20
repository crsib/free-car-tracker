package me.crsib.freecartracker.ui.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.ui.graphics.vector.ImageVector
import me.crsib.freecartracker.domain.model.EntryKind

/** Leading icon used for an entry in lists (recent entries, history). */
fun EntryKind.icon(): ImageVector = when (this) {
    EntryKind.FUEL -> Icons.Filled.LocalGasStation
    EntryKind.CHARGE -> Icons.Filled.EvStation
    EntryKind.EXPENSE -> Icons.AutoMirrored.Filled.ReceiptLong
}

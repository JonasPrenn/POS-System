package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.ui.theme.TouchTarget
import com.example.vereins_kassensystem.ui.icons.VdIcons

/**
 * The primary action on a management row.
 *
 * Sized to [TouchTarget.min] rather than the Material default of 40dp. These sit at the
 * right edge of a row, which is where a thumb lands least accurately, and the row beside
 * them is itself tappable — an undersized button here means editing when you meant to
 * top up.
 */
@Composable
fun VdIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(TouchTarget.min),
        shape = MaterialTheme.shapes.small,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
    }
}

/** One entry in a [VdRowMenu]. [destructive] paints it in the error colour. */
data class RowMenuItem(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * The rare actions on a management row, behind one target.
 *
 * Products, members and categories each carried a cluster of three tonal icon buttons —
 * nine buttons in total, all built separately, all below the accessible minimum. Only one
 * action per row is frequent (topping up a Deckel, editing a product); rename and delete
 * are admin work that happens once. Collapsing them into a menu buys back the width the
 * name needs on a phone, and puts the destructive one behind a deliberate second tap.
 */
@Composable
fun VdRowMenu(
    items: List<RowMenuItem>,
    modifier: Modifier = Modifier,
    contentDescription: String = "Weitere Aktionen"
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(TouchTarget.min)
        ) {
            Icon(VdIcons.MoreVert, contentDescription = contentDescription)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                val tint = if (item.destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                DropdownMenuItem(
                    text = { Text(item.label, color = tint) },
                    leadingIcon = { Icon(item.icon, contentDescription = null, tint = tint) },
                    onClick = {
                        expanded = false
                        item.onClick()
                    }
                )
            }
        }
    }
}

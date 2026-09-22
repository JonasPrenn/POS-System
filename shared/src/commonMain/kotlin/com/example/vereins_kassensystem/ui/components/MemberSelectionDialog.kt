package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.displayName
import com.example.vereins_kassensystem.data.entity.matches
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.icons.VdIcons
import com.example.vereins_kassensystem.ui.theme.Spacing

/**
 * Ein Mitglied aus der Liste wählen, mit Suche nach Name und Couleurname. Dient dem Verkauf
 * (wer anschreibt) wie dem Bardienst (wer die Theke übernimmt): Beides sind Mitglieder, und
 * ein Name, der nicht in der Liste steht, kommt nicht in eine Buchung.
 */
@Composable
fun MemberSelectionDialog(
    members: List<Member>,
    onDismiss: () -> Unit,
    onMemberSelected: (Member) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, members) {
        members.filter { it.matches(query) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mitglied auswählen") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Suche") },
                    leadingIcon = { Icon(VdIcons.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.md))
                if (filtered.isEmpty()) {
                    Text(
                        text = "Keine Mitglieder gefunden.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it.id }) { member ->
                            ListItem(
                                headlineContent = { Text(member.displayName) },
                                supportingContent = { Text(if (member.isBlocked) "${Money.format(member.balance)} · Deckel gesperrt" else Money.format(member.balance)) },
                                leadingContent = { MemberAvatar(member.name, size = 36.dp) },
                                modifier = Modifier.clickable {
                                    onMemberSelected(member)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Schließen") } }
    )
}

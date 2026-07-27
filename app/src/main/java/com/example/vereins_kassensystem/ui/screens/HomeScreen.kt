package com.example.vereins_kassensystem.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSales: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToProducts: () -> Unit,
    onNavigateToMembers: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("VereinsDeckel") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onNavigateToSales,
                modifier = Modifier.fillMaxWidth(0.8f).padding(8.dp)
            ) {
                Text("Verkauf")
            }
            Button(
                onClick = onNavigateToHistory,
                modifier = Modifier.fillMaxWidth(0.8f).padding(8.dp)
            ) {
                Text("Transaktionsverlauf")
            }
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onNavigateToProducts,
                modifier = Modifier.fillMaxWidth(0.8f).padding(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Produktverwaltung")
            }
            Button(
                onClick = onNavigateToMembers,
                modifier = Modifier.fillMaxWidth(0.8f).padding(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Mitgliederverwaltung")
            }
        }
    }
}

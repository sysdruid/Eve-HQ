package eve.hq.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eve.hq.app.assets.AssetsViewModel

@Composable
fun AssetsScreen(viewModel: AssetsViewModel) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.weight(1f),
                label = { Text("Search assets") },
                singleLine = true,
            )
            Button(onClick = { viewModel.refresh() }, enabled = !state.loading) { Text("Refresh") }
        }
        if (state.loading) {
            Text("Loading assets…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.snapshots.forEach { snapshot ->
            Text("${snapshot.characterName}: inventory updated ${snapshot.updatedAt}")
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.failures.forEach { failure ->
            Text("${failure.characterName}: ${failure.message}", color = MaterialTheme.colorScheme.error)
        }
        if (!state.loading && state.items.isEmpty() && state.failures.isEmpty() && state.error == null) {
            Text("No assets found.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.items) { row ->
                val location = listOf(row.characterName, row.stationName, row.containerPath)
                    .filter(String::isNotEmpty).joinToString(" - ")
                Text("$location: ${row.typeName}  ${row.quantity}")
            }
        }
    }
}
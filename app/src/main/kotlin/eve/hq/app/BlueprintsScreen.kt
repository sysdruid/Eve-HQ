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
import androidx.compose.material3.Checkbox
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
import eve.hq.app.blueprints.BlueprintsViewModel

@Composable
fun BlueprintsScreen(viewModel: BlueprintsViewModel) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.weight(1f),
                label = { Text("Search blueprints") },
                singleLine = true,
            )
            Checkbox(checked = state.freeOnly, onCheckedChange = viewModel::setFreeOnly)
            Text("Free only")
            Button(onClick = viewModel::refresh, enabled = !state.loading) { Text("Refresh") }
        }
        if (state.loading) {
            Text("Loading blueprints…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.failures.forEach { failure ->
            Text("${failure.characterName}: ${failure.message}", color = MaterialTheme.colorScheme.error)
        }
        if (!state.loading && state.lines.isEmpty() && state.failures.isEmpty() && state.error == null) {
            Text("No blueprints found.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.lines) { line ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BlueprintIcon(line.blueprintTypeId, copy = line.copy, size = 32.dp)
                    Text(
                        line.text,
                        color = if (line.inUse) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

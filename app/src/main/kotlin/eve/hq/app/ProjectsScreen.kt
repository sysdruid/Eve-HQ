package eve.hq.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eve.hq.app.projects.ProjectsViewModel

@Composable
fun ProjectsScreen(viewModel: ProjectsViewModel) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.title.ifEmpty { "Projects" }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { viewModel.refresh() }, enabled = !state.loading) { Text("Refresh") }
        }
        if (state.loading) {
            Text("Planning…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.failures.forEach { failure ->
            Text(
                "${failure.characterName}: ${failure.message} (on hand may be too low)",
                color = MaterialTheme.colorScheme.error,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text("Goals", style = MaterialTheme.typography.titleMedium) }
            items(state.goals) { Text(it) }
            state.phases.forEach { phase ->
                item {
                    Text(
                        "${phase.title} (runs in parallel with the other phases)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(phase.jobs) { Text(it) }
            }
            item { Text("Shopping list", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            if (state.shopping.isEmpty() && !state.loading && state.error == null) item { Text("Nothing to buy.") }
            items(state.shopping) { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(statusLabel(line.status), color = statusColor(line.status))
                    Text(line.text)
                }
            }
        }
    }
}

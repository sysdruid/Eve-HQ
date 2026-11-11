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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            state.projects.forEach { choice ->
                if (choice.id == state.selected) {
                    Button(onClick = { viewModel.select(choice.id) }) { Text(choice.title) }
                } else {
                    OutlinedButton(onClick = { viewModel.select(choice.id) }) { Text(choice.title) }
                }
            }
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
        // Goals, the shopping list (what to get before anything starts), then one tab per phase; the phases run in
        // parallel, so they are tabs, not steps.
        val lastTab = state.phases.size + 1 // goals and the shopping list are tabs 0 and 1, the phases follow
        var selectedTab by remember(state.selected) { mutableIntStateOf(0) }
        val tab = selectedTab.coerceIn(0, lastTab)
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            Tab(selected = tab == 0, onClick = { selectedTab = 0 }, text = { Text("Goals (${state.goals.size})") })
            Tab(selected = tab == 1, onClick = { selectedTab = 1 }, text = { Text("Shopping list (${state.shopping.size})") })
            state.phases.forEachIndexed { index, phase ->
                Tab(
                    selected = tab == index + 2,
                    onClick = { selectedTab = index + 2 },
                    text = { Text("${phase.title} (${phase.jobs.size})") },
                )
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (tab) {
                0 -> items(state.goals) { Text(it) }
                1 -> {
                    if (state.shopping.isEmpty() && !state.loading && state.error == null) item { Text("Nothing to buy.") }
                    items(state.shopping) { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(statusLabel(line.status), color = statusColor(line.status))
                            Text(line.text)
                        }
                    }
                }
                else -> {
                    item {
                        Text(
                            "Runs in parallel with the other phases.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    items(state.phases[tab - 2].jobs) { Text(it) }
                }
            }
        }
    }
}

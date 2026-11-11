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
        state.timeFailures.forEach { failure ->
            Text(
                "${failure.characterName}: ${failure.message} (job times may be longer than in the game)",
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.failures.forEach { failure ->
            Text(
                "${failure.characterName}: ${failure.message} (on hand may be too low)",
                color = MaterialTheme.colorScheme.error,
            )
        }
        // Goals, the prerequisites (what to have before anything starts), then one tab per phase; the phases run in
        // parallel, so they are tabs, not steps.
        // Goals and the prerequisites are tabs 0 and 1, the phases follow, and the running hull jobs come last.
        val lastTab = state.phases.size + 2
        var selectedTab by remember(state.selected) { mutableIntStateOf(0) }
        val tab = selectedTab.coerceIn(0, lastTab)
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            Tab(selected = tab == 0, onClick = { selectedTab = 0 }, text = { Text("Goals (${state.goals.size})") })
            Tab(selected = tab == 1, onClick = { selectedTab = 1 }, text = { Text("Prerequisites (${state.prerequisites.groups.sumOf { it.lines.size }})") })
            state.phases.forEachIndexed { index, phase ->
                Tab(
                    selected = tab == index + 2,
                    onClick = { selectedTab = index + 2 },
                    text = { Text("${phase.title} (${phase.toStart.size})") },
                )
            }
            Tab(
                selected = tab == lastTab,
                onClick = { selectedTab = lastTab },
                text = { Text("Hull jobs (${state.hullJobs.size})") },
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (tab) {
                0 -> items(state.goals) { line -> ItemRow(line.item) { Text(line.text) } }
                1 -> {
                    val prerequisites = state.prerequisites
                    if (prerequisites.groups.isEmpty() && !state.loading && state.error == null) item { Text("No prerequisites missing.") }
                    if (prerequisites.allMultibuy.isNotEmpty()) {
                        item {
                            OutlinedButton(onClick = { copyToClipboard(prerequisites.allMultibuy) }) { Text("Copy all as multibuy") }
                        }
                    }
                    prerequisites.groups.forEach { group ->
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Text("${group.name} (${group.lines.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                if (group.multibuy.isNotEmpty()) {
                                    OutlinedButton(onClick = { copyToClipboard(group.multibuy) }) { Text("Copy multibuy") }
                                }
                            }
                        }
                        items(group.lines) { line ->
                            ItemRow(line.item) {
                                Text(statusLabel(line.status), color = statusColor(line.status))
                                Text(line.text)
                            }
                        }
                    }
                }
                lastTab -> {
                    if (state.hullJobsSummary.isNotEmpty()) item { Text(state.hullJobsSummary, style = MaterialTheme.typography.titleMedium) }
                    state.jobFailures.forEach { failure ->
                        item { Text("${failure.characterName}: ${failure.message}", color = MaterialTheme.colorScheme.error) }
                    }
                    if (state.hullJobs.isEmpty() && !state.loading && state.error == null) item { Text("No hull jobs running.") }
                    items(state.hullJobs) { line -> JobLineItem(line) }
                }
                else -> {
                    val phase = state.phases[tab - 2]
                    item { Text("Runs in parallel with the other phases.", style = MaterialTheme.typography.bodySmall) }
                    if (phase.ready.isNotEmpty()) {
                        item { Text("Ready to deliver", style = MaterialTheme.typography.titleMedium) }
                        items(phase.ready) { line -> JobLineItem(line) }
                    }
                    if (phase.running.isNotEmpty()) {
                        item { Text("Running", style = MaterialTheme.typography.titleMedium) }
                        items(phase.running) { line -> JobLineItem(line) }
                    }
                    item { Text("To start", style = MaterialTheme.typography.titleMedium) }
                    if (phase.toStart.isEmpty()) item { Text("Nothing to start.") }
                    items(phase.toStart) { line -> ItemRow(line.item) { Text(line.text) } }
                }
            }
        }
    }
}

/** An item's icon beside what is said about it, the same way on every list. */
@Composable
private fun ItemRow(item: Long?, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TypeIcon(item, size = 32.dp)
        content()
    }
}

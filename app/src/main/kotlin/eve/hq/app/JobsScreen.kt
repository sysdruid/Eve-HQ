package eve.hq.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eve.hq.app.jobs.JobLine
import eve.hq.app.jobs.JobLineStatus
import eve.hq.app.jobs.JobsViewModel

@Composable
fun JobsScreen(viewModel: JobsViewModel) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Running industry jobs", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Button(onClick = viewModel::refresh, enabled = !state.loading) { Text("Refresh") }
        }
        if (state.loading) {
            Text("Loading jobs…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.failures.forEach { failure ->
            Text("${failure.characterName}: ${failure.message}", color = MaterialTheme.colorScheme.error)
        }
        if (!state.loading && state.lines.isEmpty() && state.failures.isEmpty() && state.error == null) {
            Text("No running jobs.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.lines) { line -> JobLineItem(line) }
        }
    }
}

// Fixed pastel colours with dark text: readable regardless of theme until the GUI gets a real design pass.
private val TRACK = Color(0xFFF1F3F4)
private val TEXT = Color(0xFF1B1B1B)

private fun JobLineStatus.barColor() = when (this) {
    JobLineStatus.Running -> Color(0xFFBBDEFB)
    JobLineStatus.Paused -> Color(0xFFCFD8DC)
    JobLineStatus.Ready -> Color(0xFFC8E6C9)
}

@Composable
internal fun JobLineItem(line: JobLine) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(TRACK)) {
        // matchParentSize pins the constraints to the full row, so the fraction must be applied to a child.
        Box(Modifier.matchParentSize()) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(line.progress).background(line.status.barColor()))
        }
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            line.characterId?.let { CharacterPortrait(it, size = 32.dp) }
            line.productId?.let { TypeIcon(it, size = 32.dp) }
            Text(line.text, color = TEXT)
        }
    }
}

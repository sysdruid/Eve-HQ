package eve.hq.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eve.hq.app.stockpiles.StockpileForm
import eve.hq.app.stockpiles.StockpilesViewModel
import eve.hq.core.asset.AssetLocation
import eve.hq.core.production.StockpileStatus

@Composable
fun StockpilesScreen(viewModel: StockpilesViewModel, onShowAssets: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = viewModel::startAdd, enabled = state.form == null) { Text("Add stockpile") }
            OutlinedButton(onClick = { viewModel.refresh() }, enabled = !state.loading) { Text("Refresh") }
        }
        state.form?.let { StockpileFormPanel(it, state.stations, viewModel) }
        if (state.loading) {
            Text("Loading stockpiles…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.failures.forEach { failure ->
            Text(
                "${failure.characterName}: ${failure.message} (on hand may be too low)",
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!state.loading && state.lines.isEmpty() && state.error == null) {
            Text("No stockpiles yet. Add one to watch an item's stock.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.lines) { line ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TypeIcon(line.key.item, size = 32.dp)
                    Text(statusLabel(line.status), color = statusColor(line.status), modifier = Modifier.padding(end = 4.dp))
                    // Clicking the name jumps to the assets of this item, to see where it is scattered.
                    Text(line.text, modifier = Modifier.weight(1f).clickable { onShowAssets(line.name) })
                    OutlinedButton(onClick = { viewModel.edit(line.key) }) { Text("Edit") }
                    OutlinedButton(onClick = { viewModel.remove(line.key) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun StockpileFormPanel(form: StockpileForm, stations: List<AssetLocation>, viewModel: StockpilesViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val item = form.item
        if (item == null) {
            var query by remember { mutableStateOf("") }
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.searchItems(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search item") },
                singleLine = true,
            )
            form.suggestions.forEach { suggestion ->
                Row(
                    Modifier.fillMaxWidth().clickable { viewModel.chooseItem(suggestion) }.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TypeIcon(suggestion.id, size = 32.dp)
                    Text(suggestion.name)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TypeIcon(item.id, size = 32.dp)
                Text(item.name, style = MaterialTheme.typography.titleMedium)
            }
        }
        StationPicker(form.station, stations, viewModel::chooseStation)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LevelField("Required", form.required, viewModel::setRequired, Modifier.weight(1f))
            LevelField("Preferred (optional)", form.preferred, viewModel::setPreferred, Modifier.weight(1f))
            LevelField("Overflow (optional)", form.overflow, viewModel::setOverflow, Modifier.weight(1f))
        }
        form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = viewModel::save) { Text("Save") }
            OutlinedButton(onClick = viewModel::cancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun StationPicker(selected: AssetLocation?, stations: List<AssetLocation>, onChoose: (AssetLocation?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Station: ${selected?.let(::placeName) ?: "Anywhere"}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Anywhere") }, onClick = { onChoose(null); open = false })
            stations.forEach { station ->
                DropdownMenuItem(text = { Text(placeName(station)) }, onClick = { onChoose(station); open = false })
            }
        }
    }
}

private fun placeName(location: AssetLocation) = location.name ?: "Location ${location.id}"

@Composable
private fun LevelField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(value = value, onValueChange = onChange, modifier = modifier, label = { Text(label) }, singleLine = true)
}

internal fun statusLabel(status: StockpileStatus) = when (status) {
    StockpileStatus.BelowRequired -> "LOW"
    StockpileStatus.BelowPreferred -> "LOWISH"
    StockpileStatus.AboveOverflow -> "SURPLUS"
    StockpileStatus.Ok -> "OK"
}

internal fun statusColor(status: StockpileStatus) = when (status) {
    StockpileStatus.BelowRequired -> Color(0xFFC62828)
    StockpileStatus.BelowPreferred -> Color(0xFFEF6C00)
    StockpileStatus.AboveOverflow -> Color(0xFF1565C0)
    StockpileStatus.Ok -> Color(0xFF2E7D32)
}

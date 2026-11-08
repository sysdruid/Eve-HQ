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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eve.hq.app.characters.CharactersViewModel
import eve.hq.app.characters.LoginState

@Composable
fun CharactersScreen(viewModel: CharactersViewModel) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state.login) {
                LoginState.WaitingForBrowser -> {
                    Text("Waiting for the browser login…")
                    OutlinedButton(onClick = viewModel::cancelLogin) { Text("Cancel") }
                }
                else -> Button(onClick = viewModel::addCharacter) { Text("Add character") }
            }
        }
        (state.login as? LoginState.Failed)?.let {
            Text(it.message, color = MaterialTheme.colorScheme.error)
        }
        if (state.characters.isEmpty()) {
            Text("No characters yet. Add one to get started.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.characters, key = { it.id }) { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(row.name, style = MaterialTheme.typography.titleMedium)
                        row.problem?.let {
                            Text("Needs login again: $it", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { viewModel.remove(row.id) }) { Text("Remove") }
                }
            }
        }
    }
}

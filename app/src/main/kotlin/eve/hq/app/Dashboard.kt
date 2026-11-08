package eve.hq.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun Dashboard(viewModels: AppViewModels) {
    var selectedTab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Characters") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Assets") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                0 -> CharactersScreen(viewModels.characters)
                1 -> AssetsScreen(viewModels.assets.value)
            }
        }
    }
}
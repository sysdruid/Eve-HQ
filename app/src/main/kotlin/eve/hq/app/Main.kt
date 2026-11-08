package eve.hq.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import eve.hq.data.DataDir
import eve.hq.data.Platform

fun main() {
    val dataDir = DataDir.open(Platform.current())
    val services = runCatching { AppServices.create(dataDir) }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Eve-HQ") {
            MaterialTheme {
                services.fold(
                    onSuccess = { Dashboard(it) },
                    onFailure = { Text(it.message ?: it.toString(), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) },
                )
            }
        }
    }
}

package eve.hq.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import eve.hq.data.DataDir
import eve.hq.data.FileDatabase
import eve.hq.data.Platform
import eve.hq.data.StaticDataImporter
import java.nio.file.Path
import java.time.Clock

fun main(args: Array<String>) {
    val dataDir = DataDir.open(Platform.current())
    if (runCommand(args, dataDir)) return
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

internal fun runCommand(args: Array<String>, dataDir: Path): Boolean {
    if (args.isEmpty()) return false
    require(args.size == 2 && args[0] == "import-static-data") {
        "Usage: import-static-data <sde-jsonl.zip>"
    }
    val database = FileDatabase.open(dataDir, Clock.systemUTC())
    StaticDataImporter(database.dataSource).importArchive(Path.of(args[1]))
    println("Imported static data from ${args[1]}")
    return true
}

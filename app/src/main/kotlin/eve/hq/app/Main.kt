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
import eve.hq.data.StaticDataDownloadResult
import eve.hq.data.StaticDataImporter
import eve.hq.data.downloadStaticData
import java.nio.file.Path
import java.time.Clock
import java.util.logging.Level
import java.util.logging.Logger

internal const val LATEST_SDE_URL = "https://developers.eveonline.com/static-data/eve-online-static-data-latest-jsonl.zip"

fun main(args: Array<String>) {
    val dataDir = DataDir.open(Platform.current())
    AppLog.install(dataDir)
    if (runCommand(args, dataDir)) return
    val services = runCatching { AppServices.create(dataDir) }
        .onFailure { Logger.getLogger("eve.hq.app").log(Level.SEVERE, "Startup failed", it) }
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

internal fun runCommand(
    args: Array<String>,
    dataDir: Path,
    latestUrl: String = LATEST_SDE_URL,
): Boolean {
    if (args.isEmpty()) return false
    require(args.size <= 2 && args[0] == "import-static-data") {
        "Usage: import-static-data [sde-jsonl.zip or URL]"
    }
    val database = FileDatabase.open(dataDir, Clock.systemUTC())
    val importer = StaticDataImporter(database.dataSource)
    val source = args.getOrNull(1) ?: latestUrl
    if (source.startsWith("https://") || source.startsWith("http://")) {
        when (downloadStaticData(source, dataDir, importer)) {
            StaticDataDownloadResult.IMPORTED -> println("Imported static data from $source")
            StaticDataDownloadResult.NOT_MODIFIED -> println("Static data is already up to date")
        }
    } else {
        importer.importArchive(Path.of(source))
        println("Imported static data from $source")
    }
    return true
}

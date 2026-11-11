package eve.hq.app

import java.nio.file.Path
import java.util.logging.FileHandler
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/** Writes warnings, caught failures and crashes to `eve-hq.0.log` (newest) in the data directory, rotating over 3 files of up to 1 MB. */
object AppLog {
    private var handler: Handler? = null

    fun install(dataDir: Path) {
        val root = Logger.getLogger("")
        handler?.let { root.removeHandler(it); it.close() }
        handler = FileHandler(dataDir.resolve("eve-hq.%g.log").toString(), 1_000_000, 3, true)
            .apply { formatter = SimpleFormatter() }
            .also(root::addHandler)
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            Logger.getLogger("eve.hq.app").log(Level.SEVERE, "Uncaught exception in ${thread.name}", failure)
        }
    }

    fun flush() = handler?.flush()
}

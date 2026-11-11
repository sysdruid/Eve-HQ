package eve.hq.app

import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Files
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.readText
import kotlin.test.Test

class AppLogTest {
    @Test
    fun `a logged failure lands in the log file in the data directory with its stack trace`() {
        val dir = Files.createTempDirectory("eve-hq-log")

        AppLog.install(dir)
        Logger.getLogger("test").log(Level.WARNING, "Unable to load stockpiles", IllegalStateException("ESI exploded"))
        AppLog.flush()

        val log = dir.resolve("eve-hq.0.log").readText()
        assertThat(log).contains("Unable to load stockpiles", "IllegalStateException: ESI exploded", "at eve.hq.app.AppLogTest")
    }
}

package eve.hq.data

import org.h2.jdbcx.JdbcDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

class FileDatabase private constructor(val dataSource: DataSource) {
    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        fun open(dir: Path, clock: Clock, migrationLocation: String = Migrations.DEFAULT_LOCATION): FileDatabase {
            val file = dir.resolve("eve-hq.mv.db")
            // Decide before connecting: connecting creates the file.
            val existed = Files.exists(file)
            val dataSource = JdbcDataSource().apply { setURL("jdbc:h2:file:${dir.resolve("eve-hq")}") }
            Migrations.migrate(dataSource, migrationLocation) {
                if (existed) backUp(file, dir.resolve("backups"), clock)
            }
            return FileDatabase(dataSource)
        }

        private fun backUp(file: Path, backups: Path, clock: Clock) {
            Files.createDirectories(backups)
            Files.copy(file, backups.resolve("eve-hq-${STAMP.format(clock.instant())}.mv.db"))
        }
    }
}

package eve.hq.data

import org.flywaydb.core.Flyway
import javax.sql.DataSource

object Migrations {
    const val DEFAULT_LOCATION = "classpath:db/migration"

    /**
     * Brings the schema up to date using the versioned scripts at [location].
     * [beforeMigrating] runs only when there is something to apply, and before anything is changed.
     */
    fun migrate(dataSource: DataSource, location: String = DEFAULT_LOCATION, beforeMigrating: () -> Unit = {}) {
        val flyway = Flyway.configure().dataSource(dataSource).locations(location).load()
        if (flyway.info().pending().isNotEmpty()) beforeMigrating()
        flyway.migrate()
    }
}

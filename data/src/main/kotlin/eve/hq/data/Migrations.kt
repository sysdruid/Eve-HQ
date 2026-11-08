package eve.hq.data

import org.flywaydb.core.Flyway
import javax.sql.DataSource

object Migrations {
    /** Brings the database schema up to date using the versioned scripts in `db/migration`. */
    fun migrate(dataSource: DataSource) {
        Flyway.configure().dataSource(dataSource).load().migrate()
    }
}

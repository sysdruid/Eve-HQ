package eve.hq.data

import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileDatabaseTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)

    private val cipher = TokenCipher(ByteArray(32) { it.toByte() })

    private fun repository(dataSource: javax.sql.DataSource) = JooqCharacterRepository(dataSource, cipher)

    private val alice = EveCharacter(
        id = 1,
        name = "Alice",
        token = AuthToken("access", "refresh", Instant.parse("2026-01-01T00:00:00Z")),
    )

    @Test
    fun `given characters stored in the database file, when it is reopened, they are still listed`(@TempDir dir: Path) {
        val first = FileDatabase.open(dir, clock)
        repository(first.dataSource).save(alice)

        val reopened = FileDatabase.open(dir, clock)

        assertEquals(listOf(alice), repository(reopened.dataSource).all())
    }

    private fun scripts(dir: Path, name: String, vararg files: Pair<String, String>): String {
        val location = Files.createDirectories(dir.resolve(name))
        files.forEach { (file, sql) -> Files.writeString(location.resolve(file), sql) }
        return "filesystem:$location"
    }

    private val v1 = "V1__characters.sql" to Files.readString(
        Path.of("src/main/resources/db/migration/V1__characters.sql"),
    )
    private val v2 = "V2__notes.sql" to "CREATE TABLE notes (id BIGINT PRIMARY KEY, body VARCHAR(100) NOT NULL);"

    private fun backups(dir: Path): List<Path> =
        dir.resolve("backups").takeIf(Files::isDirectory)?.let { Files.list(it).use { s -> s.toList() } } ?: emptyList()

    @Test
    fun `given an existing database with pending migrations, a backup copy is made before migrating`(@TempDir dir: Path) {
        val onlyV1 = scripts(dir, "v1-only", v1)
        val withV2 = scripts(dir, "v1-and-v2", v1, v2)
        repository(FileDatabase.open(dir, clock, onlyV1).dataSource).save(alice)

        FileDatabase.open(dir, clock, withV2)

        assertEquals(listOf("eve-hq-20261108T100000Z.mv.db"), backups(dir).map { it.fileName.toString() })
    }

    private fun inspect(backup: Path): Pair<List<String>, List<String>> {
        val base = backup.toString().removeSuffix(".mv.db")
        val ds = JdbcDataSource().apply { setURL("jdbc:h2:file:$base;ACCESS_MODE_DATA=r") }
        return ds.connection.use { c ->
            val names = c.createStatement().executeQuery("select name from characters").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
            val tables = c.createStatement()
                .executeQuery("select table_name from information_schema.tables where table_schema = 'PUBLIC'")
                .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            names to tables
        }
    }

    @Test
    fun `the backup holds the data as it was before the migration`(@TempDir dir: Path) {
        val onlyV1 = scripts(dir, "v1-only", v1)
        val withV2 = scripts(dir, "v1-and-v2", v1, v2)
        repository(FileDatabase.open(dir, clock, onlyV1).dataSource).save(alice)
        FileDatabase.open(dir, clock, withV2)

        val (names, tables) = inspect(backups(dir).single())

        assertEquals(listOf("Alice"), names)
        assertEquals(false, "NOTES" in tables, "backup must not contain the migrated table: $tables")
    }

    @Test
    fun `given a new database, no backup is made`(@TempDir dir: Path) {
        FileDatabase.open(dir, clock)

        assertEquals(emptyList(), backups(dir))
    }

    @Test
    fun `given nothing to migrate, no backup is made`(@TempDir dir: Path) {
        FileDatabase.open(dir, clock)

        FileDatabase.open(dir, clock)

        assertEquals(emptyList(), backups(dir))
    }

    @Test
    fun `given a migration that fails, the error is reported and the backup keeps the earlier data`(@TempDir dir: Path) {
        val onlyV1 = scripts(dir, "v1-only", v1)
        val broken = scripts(dir, "v1-and-broken", v1, "V2__broken.sql" to "THIS IS NOT SQL;")
        repository(FileDatabase.open(dir, clock, onlyV1).dataSource).save(alice)

        assertFailsWith<Exception> { FileDatabase.open(dir, clock, broken) }

        assertEquals(listOf("Alice"), inspect(backups(dir).single()).first)
    }

    private val v2Widen = "V2__widen_token_columns.sql" to Files.readString(
        Path.of("src/main/resources/db/migration/V2__widen_token_columns.sql"),
    )

    @Test
    fun `widening the token columns keeps existing characters and keeps the columns required`(@TempDir dir: Path) {
        repository(FileDatabase.open(dir, clock, scripts(dir, "v1-only", v1)).dataSource).save(alice)

        val migrated = FileDatabase.open(dir, clock, scripts(dir, "v1-and-v2", v1, v2Widen))

        assertEquals(listOf(alice), repository(migrated.dataSource).all())
        val nullable = migrated.dataSource.connection.use { c ->
            c.createStatement().executeQuery(
                "select column_name, is_nullable from information_schema.columns " +
                    "where table_name = 'CHARACTERS' and column_name in ('ACCESS_TOKEN', 'REFRESH_TOKEN') order by column_name",
            ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
        }
        assertEquals(listOf("ACCESS_TOKEN" to "NO", "REFRESH_TOKEN" to "NO"), nullable)
    }
}

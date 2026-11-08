# Testing data migrations

Liquibase and Flyway check that a migration applies; `JDBMigrationEngine` tests what it does to
data that's already there — a backfill, a split column, a type conversion. Distilled from the
upstream `examples/09-liquibase-data-migration` and `examples/10-flyway-data-migration`.

## Full example (Liquibase)

Changelog excerpt — changeset 003 is the migration under test:

```yaml
databaseChangeLog:
  - changeSet:
      id: 001-create-person
      # createTable person(id, first_name, last_name)
  - changeSet:
      id: 002-add-full-name-column
      # addColumn full_name
  - changeSet:
      id: 003-backfill-full-name
      author: app
      changes:
        - sql:
            sql: UPDATE person SET full_name = first_name || ' ' || last_name
```

Test:

```java
class PersonFullNameBackfillMigrationTest {

    private static final String BACKFILL_CHANGESET = "003-backfill-full-name";

    // The schema as it is right before the migration - only what the test seeds.
    private interface IPersonBeforeSchema extends IDBSchema {
        IPersonBeforeRecord person();

        interface IPersonBeforeRecord extends IDBRecord {
            IPersonBeforeRecord id(int value);
            IPersonBeforeRecord first_name(String value);
            IPersonBeforeRecord last_name(String value);
        }
    }

    // The schema right after it - only what the test asserts.
    private interface IPersonAfterSchema extends IDBSchema {
        IPersonAfterRecord person();

        interface IPersonAfterRecord extends IDBRecord {
            IPersonAfterRecord id(int value);
            IPersonAfterRecord full_name(String value);
        }
    }

    private final JDBMigrationEngine<IPersonBeforeSchema, IPersonAfterSchema> migration =
            JDBMigrationEngine.builder(IPersonBeforeSchema.class, IPersonAfterSchema.class)
                    .dataSource(dataSource) // the production DBMS, e.g. from Testcontainers
                    .migrations("db/changelog.yaml")
                    .build();

    @BeforeEach
    void startFromBlankDatabase() {
        migration.reset();
    }

    @Test
    void backfill_populates_full_name_from_existing_first_and_last_name() {
        migration.migrateBefore(BACKFILL_CHANGESET);
        migration.before().insertDB(db -> {
            db.person().id(1).first_name("Ada").last_name("Lovelace");
            db.person().id(2).first_name("Alan").last_name("Turing");
        });

        migration.migrateTo(BACKFILL_CHANGESET);

        migration.after().assertDBHas(db -> {
            db.person().id(1).full_name("Ada Lovelace");
            db.person().id(2).full_name("Alan Turing");
        });
    }
}
```

Flyway is the same test with `.migrations("classpath:db/migration")` and the version of the
migration under test as marker: `migrateBefore("3")` / `migrateTo("3")` for
`V3__backfill_full_name.sql`.

## Markers

- `migrateBefore(marker)` applies everything up to, but not including, the marked migration;
  `migrateTo(marker)` applies everything up to and including it.
- **Flyway**: a version, e.g. `"3"` (`"3.0"` is the same version).
- **Liquibase**: a tag (from a `tagDatabase` changeset) or a changeset, written as `id`,
  `id::author` or `path::id::author` — the shortest form that's unique in the changelog,
  includes counted. If a name is both a tag and a changeset id, prefix it: `"tag:release-2"`,
  `"changeset:release-2"`. The error message lists the matches when a reference is ambiguous.
- Use the migration's own id/version as the marker. Don't add `tagDatabase` changesets to a
  changelog just so tests have something to stop at.
- One migration under test per test: `migrateBefore(m)` and `migrateTo(m)` with the same `m`.
  There's deliberately no "migrate everything" method — a test bounded by markers keeps testing
  the same migration as more are appended later.
- An unknown marker fails before anything is applied.

## Database state between tests

- Both methods fail if the marked migration is already applied — the test would otherwise not
  exercise it. A second test against the same database fails with "already applied" unless the
  database is reset: call `migration.reset()` in `@BeforeEach`.
- `reset()` drops everything the tool manages in the schema (Liquibase `dropAll`, Flyway
  `clean`). Point the engine at a dedicated test database/schema only.
- `reset()` and `migrateTo(...)` never run implicitly.
- Run against the same DBMS (and ideally version) as production, e.g. with Testcontainers. SQL
  changesets in particular behave differently on H2 than on Oracle, PostgreSQL, etc. — a
  migration test that passes on an in-memory stand-in proves little.

## Before/after schema interfaces

- **Local**: declare them as private interfaces nested in the test class, not the app's current
  schema interfaces. A migration test describes the schema at one point in history; the app's
  interfaces keep changing with later migrations (a later rename would break or tempt edits to
  an old migration's test), and their `defaults()` may fill columns that didn't exist yet at
  that point. The duplication is intentional.
- **Only the tables the test touches, each with all its columns**: declare the tables the test
  seeds (before) or asserts (after), plus FK parent tables of seeded rows — don't copy the whole
  schema. Each declared table gets its full shape at that point in history, so the interface reads
  as the schema at that version; a column left out looks as if the migration had dropped it.
- **Minimal scripts**: set only the values the test depends on (and NOT NULL columns without a
  DB default, or the insert fails); assert only the columns the migration changes.
- Tables the interfaces leave out are only logged at DEBUG: `JDBMigrationEngine` defaults
  `unmappedTableStrategy` to `IGNORE` (since 1.3.2; older versions log a warning per table).
  Don't add tables to silence it.

## Builder

```java
JDBMigrationEngine.builder(IBeforeSchema.class, IAfterSchema.class)
        .dataSource(dataSource)
        .migrations("db/changelog.yaml")                       // detects Liquibase or Flyway
        // .migrator(new LiquibaseMigrator("db/changelog.yaml")) // if both are on the classpath
        // .migrator(new FlywayMigrator("classpath:db/migration"))
        .build();
```

- `liquibase-core` / `flyway-core` are optional dependencies of jdbscript — add the one the
  project uses (Flyway also needs its DBMS module, e.g. `flyway-database-oracle`).
- `before()` and `after()` are regular engines sharing the builder's settings, always with
  `CacheStrategy.NONE` since the schema changes between them. Configure one side only with
  `.beforeEngine(b -> ...)` / `.afterEngine(b -> ...)`.

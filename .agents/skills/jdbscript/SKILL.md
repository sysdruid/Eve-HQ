---
name: jdbscript
description: Use when writing or editing JVM test code that seeds, resets, or asserts against a relational database with jdbscript (org.jdbscript) — defining IDBSchema/IDBRecord interfaces, building a JDBEngine, or calling resetDB/insertDB/updateDB/cleanupDB/assertDBHas/assertDBHasNot. Also when testing what a Liquibase changeset or Flyway migration does to existing data (JDBMigrationEngine, migrateBefore/migrateTo). Covers schema modeling, fixtures, defaults/generators, composition, data migration tests, and multi-DBMS quirks.
---

# jdbscript

Type-safe, fluent database seeding for JVM tests. Schema is modeled as plain Java interfaces;
`JDBEngine` executes fluent "scripts" against them to seed, reset, or assert on real database
state — no XML/JSON fixtures, no raw SQL strings.

## Adding to a project

Test-scoped dependency `org.jdbscript:jdbscript:1.4.0` (Java 17+; check Maven Central for the
latest version). It needs only a `javax.sql.DataSource`, so it works with JUnit, TestNG, Spring Boot
or Testcontainers alike. For a minimal schema, engine and test, start from the Quickstart in
`reference/examples.md`.

## Core model

1. **Schema interface** extends `IDBSchema`, with one no-arg method per table returning that
   table's record interface.
2. **Record interface** extends `IDBSchema.IDBRecord`. Each fluent setter method returns `this`
   (well, the record type) so calls chain: `db.users().id(1L).username("alice")`.
3. **`JDBEngine<TSchema>`** built via `JDBEngine.builder(TSchema.class).dataSource(ds).build()`,
   used to run scripts: `engine.resetDB(...)`, `engine.insertDB(...)`, `engine.updateDB(...)`, `engine.cleanupDB()`,
   `engine.assertDBHas(...)`, `engine.assertDBHasNot(...)`.
4. A **script** is either a lambda (`Consumer<TSchema>` — `db -> { db.users()... ; }`) or a
   class that `implements TSchema` and populates rows in an instance initializer block. Rules for
   the class form:
   - If nested, it must be declared `static`.
   - It's normally `abstract` too, since it never overrides the interface's table-accessor
     methods (`users()`, `orders()`, ...) — plain Java requires that regardless of jdbscript.
   - It must have only a no-arg constructor (implicit or explicit).

## Critical rule: method name == column name, verbatim

```java
interface IOrderRecord extends IDBRecord {
    IOrderRecord user_id(Long userId); // -> column "user_id"
}
```

There is **no** camelCase→snake_case conversion and no annotation to override it. Name the Java
method exactly as the database column is named, even if that means a non-camelCase method name.
Do not "fix" `user_id` to `userId` — that silently breaks the mapping.
A method without a matching column fails on first use of the engine, listing every mismatch.

### Renaming or dropping a column or table
A rename is a method rename (setter or table method): rename the declaration, then fix the
compile errors, or use a semantic rename (IDE via MCP, language server) if one is available.
On a large code base, OpenRewrite does it in one run regardless of the number of call sites:
`ChangeMethodName` for a rename, `RemoveMethodInvocations` for a dropped column (then delete
the declaration).

## When writing schema interfaces

- One record interface per table; one accessor method per table on the schema interface.
- Every setter returns the record type, enabling chaining.
- Put defaults/generators on the record interface itself via a `default void defaults(RecordTools
  tools)` method (see below) — not on the engine or schema.

## Seeding data

```java
engine.resetDB(db -> {                 // wipes schema's tables (FK-safe order), then inserts
    db.users().id(1L).username("alice").email("alice@example.com").active(true);
    db.orders().id(101L).user_id(1L).total_amount(49.99);
});

engine.insertDB(db -> {                // inserts without wiping existing data
    db.users().id(3L).username("charlie");
});
```

Prefer `resetDB` for a test's baseline arrange step; use `insertDB` when you need to interleave
inserts with actions under test (simulating events over time within one test).

```java
engine.updateDB(db -> {
    db.users().id(1L).active(false);   // changes only `active` of user 1
});
```

Use `updateDB` to tweak a row of a shared baseline for one test, or to change data between actions
under test. The primary key columns (from DB metadata) select the row and must all be set; the other
set columns are updated; defaults are not applied. It fails if the row doesn't exist.

## Reusable / composable scripts

```java
public abstract class BaseUsersFixture implements IAppSchema {{
    users().id(1L).username("admin").active(true);
    users().id(2L).username("guest").active(true);
}};

engine.resetDB(BaseUsersFixture.class);

engine.resetDB(db -> {
    db.include(BaseUsersFixture.class); // or db.include(someLambdaScript)
    db.orders().id(200L).user_id(1L).total_amount(99.00);
});
```

`include` also works inside a class script's initializer, so fixture classes can build on each other.

A script is just Java — loops, `Random` (seed it, e.g. `new Random(42)`, for reproducible bulk
fixtures), conditionals — nothing special is needed to generate many rows.

When a helper earns its place (see "Writing high-signal fixtures"), put it as a default method on a
sub-interface (e.g. local to the test class or shared across domain tests) instead of growing the main
schema interface, and run lambdas through `as(...)`:

```java
interface IOrderScript extends IAppSchema {
    record Item(String productName, int quantity, double unitPrice) {}

    static Item item(String productName, int quantity, double unitPrice) {
        return new Item(productName, quantity, unitPrice);
    }

    default IOrderRecord orderWithItems(long orderId, Item... items) {
        double total = 0;
        for (int i = 0; i < items.length; i++) {
            Item item = items[i];
            order_items().id(orderId * 100 + i).order_id(orderId)
                    .product_name(item.productName()).quantity(item.quantity()).unit_price(item.unitPrice());
            total += item.quantity() * item.unitPrice();
        }
        return orders().id(orderId).total_amount(total);
    }
}

engine.as(IOrderScript.class).insertDB(db -> {
    db.customers().id(1L).name("Alice");
    db.orderWithItems(101L,
            item("Keyboard", 1, 120.00),
            item("Mouse", 2, 40.00)
    ).customer_id(1L).status("COMPLETED");
});
```

The view shares everything with the engine (id counters, cleanup, callbacks); the sub-interface may
only add default methods, not tables. A class script can just implement the sub-interface.

## Defaults and generators (`RecordTools`)

```java
interface IUserRecord extends IDBRecord {
    IUserRecord id(Integer id);
    IUserRecord username(String username);
    IUserRecord email(String email);

    default void defaults(RecordTools tools) {
        int nextId = tools.nextIntId("user_id_seq", 1);
        id(nextId);
        username("user_" + nextId);
        email(tools.strValue("${username}@example.com")); // templated on already-set columns
    }
}
```

`defaults(...)` runs before explicit setters in the script are applied to fill in gaps — any
column already set by the caller wins over the default. Use `tools.nextIntId(key, start)` (or
`nextLongId` for `Long` ids) for auto-incrementing IDs scoped by an arbitrary key (counters live on the engine: a later `insertDB`
continues where `resetDB` left off, and they restart on the next `cleanupDB`/`resetDB`), and `tools.strValue("${col}...")` to template a
string off another column already set on the same record.

## Assertions

Same fluent API, used to verify rather than insert:

```java
engine.assertDBHas(db -> {
    db.users().username("alice").active(true);
});
engine.assertDBHasNot(db -> {
    db.users().username("malory");
});
```

Only the columns you set are checked — omitted columns are wildcards, not implied nulls.
`defaults()` are not applied, so default values aren't checked either. Converters are applied,
just as on insert.

**Prefer asserting on your own code's behavior** (call the real system under test, assert on
what it returns) over `assertDBHas`/`assertDBHasNot`. Reach for these only when nothing in your
own code exposes the state you need to check — e.g. a DB trigger's side effect that no query in
your app surfaces.

## Cleanup and FK ordering

`engine.cleanupDB()` deletes all rows from the schema's tables in FK-safe (child-before-parent)
order, auto-detected from the database's foreign keys — you don't need to sequence cleanup
yourself. A real cycle **between** two or more tables throws; if FK auto-detection can't
determine order at all (missing FK metadata, views standing in for tables), override it
explicitly:

```java
JDBEngine.builder(IAppSchema.class)
    .dataSource(dataSource)
    .tableDependencyOrder(List.of("users", "orders")) // parent-to-child; insert order
    .build();
```

List every table the schema interface declares, parent-to-child.

To keep the rows of a declared table, such as reference data a migration created, exclude it:
`.excludeFromCleanup("brands", "order_statuses")`. Excluded tables keep their rows and identity
columns and can be asserted against. A script that writes to one fails, since those rows would
survive cleanup. Don't re-insert reference data in fixtures to work around cleanup.

## Builder options worth knowing

- `.dataSource(ds)` or `.dataSource(() -> ds)` for lazy resolution.
- `.cacheStrategy(CacheStrategy.INSTANCE|GLOBAL|NONE)` — use `GLOBAL` when schema is static
  across the whole suite (faster), `NONE` when schema changes between tests. For testing a
  migration itself, use `JDBMigrationEngine` instead (see below) — it already handles this.
- `.converter(new MyConverter())` to teach jdbscript a domain type it doesn't already handle (see
  `reference/examples.md`).

## Writing high-signal fixtures

A test's setup should be **visible** (the data the test depends on is in the test), **minimal**
(only that data; the rest goes in `defaults()`) and **self-explanatory** (readable without digging).
One check covers all three: **can you understand the test without opening the helper?** The same
check applies to fixture classes.

### Plain record chains first
Write setup as record chains with named setters, in the test. Named setters are what make a
fixture readable, so don't hide them behind one-row helpers:

```java
// ❌ BAD: what is 3? Which rows does product() create?
db.product(MALLET, "Mallet", "15.00", 3, HAND_TOOLS);

// ✅ GOOD
db.products().id(MALLET).name("Mallet").price("15.00").category_id(HAND_TOOLS);
db.inventory().product_id(MALLET).quantity(3);
```

A helper (see "Reusable / composable scripts") can help in complex scenarios. It might create
structure the test doesn't care about, or keep derived values consistent, such as an order total
computed from its items. Use one only when it makes the test simpler. Have it return the main
record, so the values the test depends on are still set with named setters:

```java
db.orderWithItems(101L,
        item("Keyboard", 1, 120.00),
        item("Mouse", 2, 40.00)
).customer_id(1L).status("COMPLETED");
```

If in doubt, write the chains.

### Background data
Data that many tests need but none asserts on (a `system` user, a settings row) can go in a
fixture class. Include it explicitly in every reset script, so each test shows where its data
comes from:

```java
engine.resetDB(db -> {
    db.include(BackgroundData.class);
    db.orders().id(200L).user_id(SYSTEM_USER).status("NEW");
});
```

If a test asserts on some data, that data belongs in the test, not in a fixture class.

### The Contrast Rule (Explicit State vs. Incidental Defaults)
1. **Explicit Test Variables**: If a column directly influences the System Under Test (SUT) or is checked in an assertion (e.g. `status`, `role`, `active`, `amount`, filter conditions), explicitly set it on **all** rows involved in the test.
   - **Visual Contrast**: Seeing `status("ACTIVE")` next to `status("INVITED")` immediately communicates the test scenario without needing to inspect external schema definitions.
   - **Default Decoupling**: Tests remain stable and won't break silently or pass for the wrong reasons when shared defaults evolve.
   ```java
   // ✅ GOOD: Contrast is explicit and obvious across all rows under test
   engine.resetDB(db -> {
       db.users().id(1L).role("ADMIN").status("ACTIVE");
       db.users().id(2L).role("MEMBER").status("ACTIVE");
       db.users().id(3L).role("MEMBER").status("INVITED");
   });

   // ❌ BAD: Relies on hidden defaults for columns actively under test
   engine.resetDB(db -> {
       db.users().id(1L).role("ADMIN");
       db.users().id(2L); // Status is hidden from the reader
       db.users().id(3L).status("INVITED");
   });
   ```
2. **Incidental Schema Defaults**: Columns required only to satisfy database constraints (`created_at`, `updated_at`, `uuid`, unique emails, synthetic slugs) belong in `defaults(RecordTools tools)` on the record interface. Do not clutter test scripts with boilerplate values that the test does not care about.

### Prefer In-Place Updates for Edge Cases
When testing an edge case, failure mode, or state mutation against an existing baseline fixture, use `engine.updateDB(...)` to mutate the relevant column in-place rather than copying and re-seeding an entire multi-table hierarchy.

### Block Formatting & Layout
- **Always use multiline lambda blocks**: Write `engine.resetDB(db -> { ... });` or `engine.updateDB(db -> { ... });` with explicit `{ ... }` blocks so the setup reads like a structured seeding script.
- **One row per line**: Chain record setters on a single line per entity (`db.users().id(1L).username("alice").active(true);`). Wrap only when an individual line gets excessively long.

## Testing data migrations

Use `JDBMigrationEngine` to test what a Liquibase changeset or Flyway migration does to rows that
already exist (e.g. a backfill) — not for ordinary fixtures; with a static schema, apply the
migrations once and use a plain `JDBEngine`. The flow, with the migration under test as marker:

```java
migration.migrateBefore("003-backfill-full-name");   // Liquibase changeset id, or Flyway version "3"
migration.before().insertDB(db -> {
    db.person().id(1).first_name("Ada").last_name("Lovelace");
});
migration.migrateTo("003-backfill-full-name");       // runs just that migration
migration.after().assertDBHas(db -> {
    db.person().id(1).full_name("Ada Lovelace");
});
```

- Before/after schema interfaces are **local**: private interfaces nested in the test class, never
  the app's current schema interfaces. They declare only the tables the test seeds or asserts
  (plus FK parents the inserts need), each with **all** its columns at that point in history.
  Keep the scripts minimal instead: set only the values the test depends on.
- Run against the **same DBMS as production** (e.g. Testcontainers), not an in-memory stand-in:
  a migration that passes on H2 can fail on Oracle.
- Both methods fail if the marker is already applied, so isolate tests with `migration.reset()`
  in `@BeforeEach`. `reset()` drops everything in the schema — dedicated test database only.

Full example, marker rules and builder options: `reference/migrations.md`.

## Gotchas

- Failures name the table and the values the script wrote (insert/update errors, `assertDBHas`
  mismatches, schema validation). Read the message before writing a probe test.
- Column-name mismatches are the most common bug: a method named `userId` will try to write to a
  column literally called `userId`, not `user_id`.
- `resetDB` wipes tables in the schema interface, not the whole database — tables absent from the
  schema interface are untouched.
- Cleanup (`cleanupDB`, and so `resetDB`) restarts sequences and identity columns at exactly 10000
  on PostgreSQL, CockroachDB, Oracle, DB2, H2, HSQLDB and DuckDB, so manually assigned low IDs (1, 2, ...)
  never collide with generated ones.
- Cleanup deletes **all rows** from the schema's tables, so tests running in parallel against the
  same database interfere with each other. Before enabling parallel execution (JUnit
  `junit.jupiter.execution.parallel.enabled`, Surefire/Gradle forks), give each fork its own
  database or schema (e.g. one Testcontainers container per fork), or keep DB tests sequential.

## Runnable references

See `reference/examples.md` in this skill directory for condensed, self-contained code snippets
covering each concern below — no network access required:

| Concern | Shows |
|---|---|
| Quickstart | Minimal schema + `resetDB`/`insertDB` arrange, then act/assert on real code |
| Class scripts & include | Class-based base fixture + `db.include(...)` composition |
| RecordTools defaults | `defaults(RecordTools)`, and explicit setters overriding defaults |
| Bulk scripting | Bulk row generation with a loop and a seeded `Random` |
| Custom converters | `IJDBTypeConverter` for a domain value type |
| Insert power | `insertDB` interleaved with actions under test |
| Testcontainers | Same schema/code as quickstart, against a real PostgreSQL container |
| Spring Boot | Dropped into `@SpringBootTest`, using the auto-configured `DataSource` bean |
| Domain DSL & helpers | Sub-interface helpers with `engine.as(...)` for multi-table aggregate generation |

`reference/migrations.md` covers data migration tests (`JDBMigrationEngine`, Liquibase/Flyway).

Kotlin works with the API as is. For receiver-lambda syntax (`engine.insert { ... }`), copy the
small extensions file from the upstream `examples/12-kotlin-dsl` — those extensions are not part
of the jdbscript jar.

For anything beyond that, the upstream project is at
[github.com/cyberdruid-dev/jdbscript](https://github.com/cyberdruid-dev/jdbscript) (README and
full runnable example modules under `examples/`).

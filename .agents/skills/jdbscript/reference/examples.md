# jdbscript example patterns

Condensed snippets distilled from jdbscript's own example suite. Use these as templates.

## Quickstart: arrange / act / assert

Schema interface:

```java
public interface IAppSchema extends IDBSchema {
    IUserRecord users();

    interface IUserRecord extends IDBRecord {
        IUserRecord id(Long id);
        IUserRecord username(String username);
        IUserRecord email(String email);
        IUserRecord active(Boolean active);
    }
}
```

Engine setup (once per test class) and the pattern most jdbscript tests actually follow:

```java
IJDBEngine<IAppSchema> engine = JDBEngine.builder(IAppSchema.class)
        .dataSource(dataSource) // any javax.sql.DataSource
        .build();

@Test
void findActiveUsernames_returns_only_active_users_in_order() {
    // Arrange: seed with jdbscript. resetDB wipes the table first, so each test starts clean.
    engine.resetDB(db -> {
        db.users().id(1L).username("bob").email("bob@example.com").active(false);
        db.users().id(2L).username("alice").email("alice@example.com").active(true);
        db.users().id(3L).username("charlie").email("charlie@example.com").active(true);
    });

    // Act: call the real system under test — jdbscript never appears here.
    List<String> result = userRepository.findActiveUsernames();

    // Assert: on the SUT's return value.
    assertEquals(List.of("alice", "charlie"), result);
}
```

Prefer asserting on your own code's behavior, as above. Reach for `assertDBHas`/`assertDBHasNot`
only when nothing in your own code exposes the state you need to check (e.g. a DB trigger's
side effect):

```java
engine.assertDBHas(db -> {
    db.users().username("alice").active(true);
});
engine.assertDBHasNot(db -> {
    db.users().username("malory");
});
```

## Reusable base fixtures + composition

A class-based script — instantiated by jdbscript, so it's fine (expected, even) for it to be
`abstract`:

```java
public abstract class BaseUsersFixture implements IAppSchema {
    {
        users().id(1L).username("admin").email("admin@example.com").active(true);
        users().id(2L).username("guest").email("guest@example.com").active(true);
    }
}
```

Run it directly, or compose it with test-specific rows via `include`:

```java
engine.resetDB(BaseUsersFixture.class);

engine.resetDB(db -> {
    db.include(BaseUsersFixture.class);
    db.orders().id(100L).user_id(1L).total_amount(49.99);
    db.orders().id(101L).user_id(1L).total_amount(10.00);
});
```

## In-place row updates (`updateDB`)

Use `updateDB` to modify specific columns of an existing row without re-seeding the entire schema. The primary key columns identify the target row, while other set columns update in-place:

```java
engine.resetDB(BaseUsersFixture.class);

// Tweak existing row in-place
engine.updateDB(db -> {
    db.users().id(1L).active(false);
});
```

## Domain DSLs & sub-interface helpers (`engine.as(...)`)

Plain record chains are the default. When a helper earns its place, e.g. keeping an order's total consistent with its items, put it on a sub-interface and return the main record, so the test still sets what it depends on by name:

```java
public interface ICustomerOrderDSL extends IAppSchema {
    record Item(String productName, int quantity, double unitPrice) {
        public static Item item(String name, int qty, double price) {
            return new Item(name, qty, price);
        }
    }

    default IOrderRecord orderWithItems(long orderId, Item... items) {
        double total = 0.0;
        long baseItemId = orderId * 100;
        for (int i = 0; i < items.length; i++) {
            Item item = items[i];
            total += item.quantity() * item.unitPrice();
            order_items().id(baseItemId + i + 1)
                    .order_id(orderId)
                    .product_name(item.productName())
                    .quantity(item.quantity())
                    .unit_price(item.unitPrice());
        }
        return orders().id(orderId).total_amount(total);
    }
}

engine.as(ICustomerOrderDSL.class).insertDB(db -> {
    db.customers().id(1L).name("Alice Smith").tier("VIP");
    db.orderWithItems(101L,
            item("Mechanical Keyboard", 1, 120.00),
            item("Wireless Mouse", 2, 40.00)
    ).customer_id(1L).status("COMPLETED");
});
```

## Defaults and generated values (`RecordTools`)

```java
interface IProductRecord extends IDBRecord {
    IProductRecord id(Integer id);
    IProductRecord sku(String sku);
    IProductRecord name(String name);

    // Runs for any column not set explicitly in the script.
    default void defaults(RecordTools tools) {
        id(tools.nextIntId("product_id", 1));
        sku(tools.strValue("SKU-${id}"));
    }
}
```

```java
engine.resetDB(db -> {
    db.products().name("Widget"); // id=1 (generated), sku="SKU-1"
    db.products().name("Gadget"); // id=2 (generated), sku="SKU-2"
});

engine.resetDB(db -> {
    // id set explicitly -> defaults() leaves it alone; sku's template still reads it -> "SKU-500"
    db.products().id(500).name("Custom Widget");
});
```

`tools.nextIntId(key, start)` advances every time `defaults()` runs, even for a record whose
generated value ends up discarded because you set that column yourself. Don't mix explicit and
generated ids in the same script if you need the sequence to stay gap-free.

## Bulk data generation

A script is plain Java — loops, helper methods, whatever generates the rows you need:

```java
engine.resetDB(db -> {
    Random random = new Random(42); // seed it — reproducibility, not "real" randomness, is the point
    for (int i = 1; i <= 100; i++) {
        db.players().id(i).username(FunNames.next(random) + "_" + i).score(random.nextInt(1000));
    }
    db.players().id(999).username("undisputed_champion").score(1_000_000);
});
```

Same seed → same rows → same assertions, on every machine, every run. `new Random()` (or
`Math.random()`) instead makes the test flaky the day it generates an edge case you didn't expect.

## Custom type converters

```java
public class MoneyConverter implements IJDBTypeConverter {
    @Override
    public boolean canConvert(Object value) {
        return value instanceof Money;
    }

    @Override
    public Object convert(Object value) {
        return BigDecimal.valueOf(((Money) value).cents(), 2);
    }
}
```

```java
engine = JDBEngine.builder(IAppSchema.class)
        .dataSource(dataSource)
        .converter(new MoneyConverter()) // adds to, doesn't replace, the built-in converters
        .build();

engine.resetDB(db -> {
    db.products().id(1).name("Widget").price(Money.dollars(19.99)).status(ProductStatus.ACTIVE);
});
```

`ProductStatus` (a plain enum) still converts via the built-in enum-to-string converter — only
`Money`, a type jdbscript has no built-in knowledge of, needed a converter of its own.

## `insertDB` for multi-step scenarios

```java
engine.resetDB(db -> {
    db.notifications().id(1).message("Welcome!");
    db.notifications().id(2).message("Your order shipped.");
});

assertEquals(2, inbox.countMessages());

// Simulate a new event arriving mid-test — insertDB adds without wiping what's there.
engine.insertDB(db -> {
    db.notifications().id(3).message("Price drop on an item you viewed!");
});

assertEquals(3, inbox.countMessages());
```

Use this to model a sequence of events over time within a single test, instead of one static
snapshot per test.

## Against a real database (Testcontainers)

Identical jdbscript code to the quickstart section — only the `DataSource`'s origin changes:

```java
@Container
private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

config.setJdbcUrl(POSTGRES.getJdbcUrl());
config.setUsername(POSTGRES.getUsername());
config.setPassword(POSTGRES.getPassword());
dataSource = new HikariDataSource(config);
```

Same schema interface, same `resetDB` call, same assertions as against in-memory H2 — jdbscript
code is portable across both DBMS and the real-vs-in-memory axis.

## Spring Boot

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UserRepositorySpringBootTest {

    @Autowired
    private DataSource dataSource; // whatever spring-boot-starter-jdbc already auto-configured

    private IJDBEngine<IAppSchema> engine;

    @BeforeEach
    void seedDatabase() {
        engine = JDBEngine.builder(IAppSchema.class).dataSource(dataSource).build();
        engine.resetDB(db -> {
            db.users().id(1L).username("bob").email("bob@example.com").active(false);
            db.users().id(2L).username("alice").email("alice@example.com").active(true);
        });
    }
}
```

No jdbscript-specific Spring integration exists or is needed — a plain `DataSource` bean is
enough. `resetDB` runs explicitly per test rather than relying on Spring's test-transaction
rollback.

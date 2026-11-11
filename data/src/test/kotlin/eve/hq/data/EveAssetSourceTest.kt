package eve.hq.data

import eve.hq.core.asset.AssetItem
import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetCache
import eve.hq.core.asset.AssetSnapshot
import eve.hq.core.asset.AssetType
import eve.hq.core.asset.CachedAssetPage
import eve.hq.core.asset.AssetListing
import eve.hq.core.asset.ListAssets
import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import eve.hq.data.esi.EveAssetSource
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import org.jdbscript.JDBEngine
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EveAssetSourceTest {
    @Test
    fun `unexpired asset pages are reused while type names are looked up independently`() = runBlocking {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        val body = """[{"item_id":101,"type_id":34,"quantity":1,"location_id":60000001,"location_type":"station"}]"""
        val cache = MemoryAssetCache(
            CachedAssetPage(1, body, "\"assets-v1\"", now.plusSeconds(3600), 1),
        )
        val esi = EsiMock {
            characterAssets(123).willReturn(
                body,
                headers = mapOf(HttpHeaders.ETag to "\"assets-v1\"", HttpHeaders.Expires to "Sun, 08 Nov 2026 13:00:00 GMT"),
            )
            universeNames().willReturn("""[{"id":34,"name":"Tritanium"}]""")
            characterAssetNames(123).willReturn("[]")
            universeStations(60000001).willReturn("""{"name":"Jita"}""")
        }
        val source = EveAssetSource(
            esi.client,
            esi.baseUrl,
            assetCache = cache,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        val result = source.load(EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX)))

        assertEquals("Tritanium", result.single().type.name)
        esi.characterAssets(123).assertNotRequested()
        esi.universeNames().assertRequested()
    }

    @Test
    fun `while every page is fresh the saved snapshot is returned without any request`() = runBlocking {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        val body = """[{"item_id":101,"type_id":34,"quantity":1,"location_id":60000001,"location_type":"station"}]"""
        val saved = listOf(AssetItem(101, AssetType(34, "Tritanium"), null, 1, AssetLocation(60000001, "Jita")))
        val cache = MemoryAssetCache(CachedAssetPage(1, body, "\"assets-v1\"", now.plusSeconds(3600), 1))
        cache.replace(123, AssetSnapshot(saved, now.minusSeconds(60)), listOf(CachedAssetPage(1, body, "\"assets-v1\"", now.plusSeconds(3600), 1)))
        val esi = EsiMock {
            characterAssets(123).willReturn(body)
            universeNames().willReturn("[]")
            characterAssetNames(123).willReturn("[]")
            universeStations(60000001).willReturn("""{"name":"Elsewhere"}""")
        }
        val source = EveAssetSource(esi.client, esi.baseUrl, assetCache = cache, clock = Clock.fixed(now, ZoneOffset.UTC))

        val result = source.load(EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX)))

        assertEquals(saved, result)
        esi.characterAssets(123).assertNotRequested()
        esi.universeNames().assertNotRequested()
        esi.characterAssetNames(123).assertNotRequested()
        esi.universeStations(60000001).assertNotRequested()
    }

    @Test
    fun `expired asset pages send their etag and reuse the saved body on not modified`() = runBlocking {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        val body = """[{"item_id":101,"type_id":34,"quantity":1,"location_id":60000001,"location_type":"station"}]"""
        val cache = MemoryAssetCache(CachedAssetPage(1, body, "\"assets-v1\"", now.minusSeconds(1), 1))
        val esi = EsiMock {
            characterAssets(123).willRespond { request ->
                assertEquals("\"assets-v1\"", request.headers[HttpHeaders.IfNoneMatch])
                EsiMock.EsiResponse(
                    body = "",
                    status = HttpStatusCode.NotModified,
                    headers = mapOf(HttpHeaders.Expires to "Sun, 08 Nov 2026 13:00:00 GMT"),
                )
            }
            universeNames().willReturn("""[{"id":34,"name":"Tritanium"}]""")
            characterAssetNames(123).willReturn("[]")
            universeStations(60000001).willReturn("""{"name":"Jita"}""")
        }
        val source = EveAssetSource(
            esi.client,
            esi.baseUrl,
            assetCache = cache,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        val result = source.load(EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX)))

        assertEquals("Tritanium", result.single().type.name)
        assertEquals(body, cache.findPage(123, 1)?.body)
        assertEquals("\"assets-v1\"", cache.findPage(123, 1)?.etag)
        assertEquals(now.plusSeconds(3600), cache.findPage(123, 1)?.expiresAt)
        assertEquals(now, cache.findSnapshot(123)?.updatedAt)
    }

    @Test
    fun `each page uses its own expiry and validator`() = runBlocking {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        val firstBody = """[{"item_id":101,"type_id":34,"quantity":1,"location_id":60000001,"location_type":"station"}]"""
        val secondBody = """[{"item_id":102,"type_id":35,"quantity":2,"location_id":60000001,"location_type":"station"}]"""
        val firstPage = CachedAssetPage(1, firstBody, "\"page-1\"", now.plusSeconds(600), 2)
        val secondPage = CachedAssetPage(2, secondBody, "\"page-2\"", now.minusSeconds(1), 2)
        val cache = MemoryAssetCache().apply {
            replace(123, AssetSnapshot(emptyList(), now.minusSeconds(100)), listOf(firstPage, secondPage))
        }
        val esi = EsiMock {
            characterAssets(123).page(2).willRespond { request ->
                assertEquals("\"page-2\"", request.headers[HttpHeaders.IfNoneMatch])
                EsiMock.EsiResponse(
                    "",
                    HttpStatusCode.NotModified,
                    mapOf(HttpHeaders.Expires to "Sun, 08 Nov 2026 13:00:00 GMT"),
                )
            }
            universeNames().willReturn("""[{"id":34,"name":"Tritanium"},{"id":35,"name":"Pyerite"}]""")
            characterAssetNames(123).willReturn("[]")
            universeStations(60000001).willReturn("""{"name":"Jita"}""")
        }
        val source = EveAssetSource(
            esi.client,
            esi.baseUrl,
            assetCache = cache,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        val result = source.load(EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX)))

        assertEquals(setOf(101L, 102L), result.map { it.itemId }.toSet())
        assertEquals(listOf("2"), esi.requests.mapNotNull { it.queryParameters["page"] })
        assertEquals(now.plusSeconds(3600), cache.findPage(123, 2)?.expiresAt)
    }

    @Test
    fun `pages without expiry are revalidated on every load`() = runBlocking {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        val body = """[{"item_id":101,"type_id":34,"quantity":1,"location_id":60000001,"location_type":"station"}]"""
        val cache = MemoryAssetCache(CachedAssetPage(1, body, "\"assets-v1\"", null, 1))
        val requestedEtags = mutableListOf<String?>()
        val esi = EsiMock {
            characterAssets(123).willRespond { request ->
                requestedEtags += request.headers[HttpHeaders.IfNoneMatch]
                EsiMock.EsiResponse(body, headers = mapOf(HttpHeaders.ETag to "\"assets-v2\""))
            }
            universeNames().willReturn("""[{"id":34,"name":"Tritanium"}]""")
            characterAssetNames(123).willReturn("[]")
            universeStations(60000001).willReturn("""{"name":"Jita"}""")
        }
        val source = EveAssetSource(
            esi.client,
            esi.baseUrl,
            assetCache = cache,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        source.load(character)
        source.load(character)

        assertEquals(listOf<String?>("\"assets-v1\"", "\"assets-v2\""), requestedEtags)
        assertNull(cache.findPage(123, 1)?.expiresAt)
    }

    @Test
    fun `not modified without a cached page is rejected`() = runBlocking {
        val cache = MemoryAssetCache()
        val esi = EsiMock {
            characterAssets(123).willReturn("", HttpStatusCode.NotModified)
        }
        val source = EveAssetSource(esi.client, esi.baseUrl, assetCache = cache)

        val error = assertFailsWith<Exception> {
            source.load(EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX)))
        }

        assertEquals("ESI character assets answered 304: ", error.message)
        assertNull(cache.findSnapshot(123))
    }

    @Test
    fun `a later page failure preserves the previous snapshot and every cached page`() = runBlocking {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        val oldPage1Body = """[{"item_id":101,"type_id":34,"quantity":1,"location_id":60000001,"location_type":"station"}]"""
        val oldPage1 = CachedAssetPage(1, oldPage1Body, "\"old-page-1\"", now.minusSeconds(1), 2)
        val oldPage2 = CachedAssetPage(2, "[]", "\"old-page-2\"", now.minusSeconds(1), 2)
        val oldSnapshot = AssetSnapshot(
            listOf(AssetItem(101, AssetType(34, "Old name"), null, 1, AssetLocation(60000001, "Old station"))),
            now.minusSeconds(100),
        )
        val cache = MemoryAssetCache().apply { replace(123, oldSnapshot, listOf(oldPage1, oldPage2)) }
        val esi = EsiMock {
            characterAssets(123).page(1).willReturn(
                """[{"item_id":202,"type_id":34,"quantity":2,"location_id":60000001,"location_type":"station"}]""",
                headers = mapOf("X-Pages" to "2", HttpHeaders.ETag to "\"new-page-1\""),
            )
            characterAssets(123).page(2).willReturn("unavailable", HttpStatusCode.ServiceUnavailable)
        }
        val source = EveAssetSource(
            esi.client,
            esi.baseUrl,
            assetCache = cache,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        assertFailsWith<Exception> {
            source.load(EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX)))
        }

        assertEquals(oldSnapshot, cache.findSnapshot(123))
        assertEquals(oldPage1, cache.findPage(123, 1))
        assertEquals(oldPage2, cache.findPage(123, 2))
    }

    @Test
    fun `a smaller page count removes obsolete pages and stores an empty snapshot`() = runBlocking {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        val cache = MemoryAssetCache().apply {
            replace(
                123,
                AssetSnapshot(
                    listOf(AssetItem(101, AssetType(34, "Tritanium"), null, 1, AssetLocation(60000001, "Jita"))),
                    now.minusSeconds(100),
                ),
                listOf(
                    CachedAssetPage(1, "[]", "\"old-page-1\"", now.minusSeconds(1), 2),
                    CachedAssetPage(2, "[]", "\"old-page-2\"", now.minusSeconds(1), 2),
                ),
            )
        }
        val esi = EsiMock {
            characterAssets(123).page(1).willReturn(
                "[]",
                headers = mapOf("X-Pages" to "1", HttpHeaders.ETag to "\"new-page-1\""),
            )
        }
        val source = EveAssetSource(
            esi.client,
            esi.baseUrl,
            assetCache = cache,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        val result = source.load(EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX)))

        assertThat(result).isEmpty()
        assertNull(cache.findPage(123, 2))
        assertThat(cache.findSnapshot(123)?.items).isEmpty()
        assertEquals(listOf(1), esi.requests.mapNotNull { it.queryParameters["page"]?.toIntOrNull() })
    }

    @Test
    fun `static names are used for known assets while ESI resolves unknown types`() = runBlocking {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        }
        Migrations.migrate(dataSource)
        val staticTypes = JDBEngine.builder(IStaticTypesTestSchema::class.java).dataSource(dataSource).build()
        staticTypes.resetDB { db ->
            db.include(StandardStaticTypes::class.java)
        }
        staticTypes.updateDB { db ->
            db.types().id(34).name("Static Tritanium")
        }
        val esi = EsiMock {
            characterAssets(123).willReturn(
                """[{"item_id":101,"type_id":34,"quantity":1,"location_id":60000001,"location_type":"station"},{"item_id":102,"type_id":999,"quantity":2,"location_id":60000001,"location_type":"station"}]""",
            )
            universeNames().willReturn("""[{"id":999,"name":"ESI Type"}]""")
            characterAssetNames(123).willReturn("[]")
            universeStations(60000001).willReturn("""{"name":"Jita"}""")
        }

        val source = EveAssetSource(esi.client, esi.baseUrl, StaticTypeNameRepository(dataSource))
        val items = source.load(EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX)))

        assertEquals(listOf("Static Tritanium", "ESI Type"), items.map { it.type.name })
        assertEquals("[999]", esi.requests.single { it.path.endsWith("/universe/names/") }.body)
    }

    @Test
    fun `source returns self-contained items with root names custom names and immediate parent ids`() = runBlocking {
        val esi = EsiMock {
            characterAssets(123).willReturn(
                """[
                    {"item_id":700,"type_id":100,"quantity":1,"location_id":60000001,"location_type":"station"},
                    {"item_id":701,"type_id":34,"quantity":42,"location_id":700,"location_type":"item"},
                    {"item_id":702,"type_id":9999,"quantity":3,"location_id":60000001,"location_type":"station"}
                ]""".trimIndent(),
            )
            universeNames().willReturn(
                """[
                    {"category":"inventory_type","id":100,"name":"Small Secure Container"},
                    {"category":"inventory_type","id":34,"name":"Tritanium"}
                ]""".trimIndent(),
            )
            characterAssetNames(123).willReturn("""[{"item_id":700,"name":"Hangar Box"}]""")
            universeStations(60000001).willReturn("""{"name":"Jita"}""")
        }
        val source = EveAssetSource(esi.client, esi.baseUrl)
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val items = source.load(character)

        assertEquals(
            listOf(
                AssetItem(
                    itemId = 700,
                    type = AssetType(100, "Small Secure Container"),
                    customName = "Hangar Box",
                    quantity = 1,
                    location = AssetLocation(60000001, "Jita"),
                ),
                AssetItem(
                    itemId = 701,
                    type = AssetType(34, "Tritanium"),
                    customName = null,
                    quantity = 42,
                    location = AssetLocation(700, null),
                ),
                AssetItem(
                    itemId = 702,
                    type = AssetType(9999, null),
                    customName = null,
                    quantity = 3,
                    location = AssetLocation(60000001, "Jita"),
                ),
            ),
            items,
        )
    }

    @Test
    fun `every asset page is loaded`() = runBlocking {
        val esi = EsiMock {
            characterAssets(123).page(1).willReturn(
                """[{"item_id":101,"type_id":34,"quantity":2,"location_id":60000001,"location_type":"station"}]""",
                headers = mapOf("X-Pages" to "2"),
            )
            characterAssets(123).page(2).willReturn(
                """[{"item_id":102,"type_id":34,"quantity":2,"location_id":60000001,"location_type":"station"}]""",
            )
            universeNames().willReturn("""[{"category":"inventory_type","id":34,"name":"Tritanium"}]""")
            characterAssetNames(123).willReturn("[]")
            universeStations(60000001).willReturn("""{"name":"Jita IV - Moon 4 - Caldari Navy Assembly Plant"}""")
        }
        val source = EveAssetSource(esi.client, esi.baseUrl)
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val items = source.load(character)

        esi.characterAssets(123).assertPagesRequested(1, 2)
        assertEquals(listOf(101L, 102L), items.map { it.itemId })
    }

    @Test
    fun `type and asset name lookups are split into batches of at most one thousand`() = runBlocking {
        val typeBatchSizes = mutableListOf<Int>()
        val itemBatchSizes = mutableListOf<Int>()
        val assets = (0..1000).joinToString(prefix = "[", postfix = "]") { index ->
            """{"item_id":${100000L + index},"type_id":${10000L + index},"quantity":1,"location_id":60000001,"location_type":"station"}"""
        }
        val esi = EsiMock {
            characterAssets(123).willReturn(
                assets,
                headers = mapOf("X-Pages" to "1"),
            )
            universeNames().willRespond { request ->
                val ids = Json.decodeFromString<List<Long>>(request.body)
                typeBatchSizes += ids.size
                EsiMock.EsiResponse(
                    ids.joinToString(prefix = "[", postfix = "]") { id ->
                        """{"category":"inventory_type","id":$id,"name":"Type $id"}"""
                    },
                )
            }
            characterAssetNames(123).willRespond { request ->
                val ids = Json.decodeFromString<List<Long>>(request.body)
                itemBatchSizes += ids.size
                EsiMock.EsiResponse("[]")
            }
            universeStations(60000001).willReturn("""{"name":"Jita"}""")
        }
        val source = EveAssetSource(esi.client, esi.baseUrl)
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val items = source.load(character)

        assertEquals(listOf(1000, 1), typeBatchSizes)
        assertEquals(listOf(1000, 1), itemBatchSizes)
        assertEquals(1001, items.mapNotNull { it.type.name }.size)
    }

    @Test
    fun `an inaccessible structure is left for the caller to display as an id fallback`() = runBlocking {
        val structureId = 1_000_000_000_001L
        val esi = EsiMock {
            characterAssets(123).willReturn(
                """[{"item_id":101,"type_id":34,"quantity":1,"location_id":$structureId,"location_type":"other"}]""",
                headers = mapOf("X-Pages" to "1"),
            )
            universeNames().willReturn("[]")
            characterAssetNames(123).willReturn("[]")
            universeStructures(structureId).willReturn("""{"error":"Forbidden"}""", HttpStatusCode.Forbidden)
        }
        val source = EveAssetSource(esi.client, esi.baseUrl)
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val items = source.load(character)

        assertEquals(1, items.size)
        assertEquals(AssetLocation(structureId, null), items.single().location)
    }

    @Test
    fun `missing asset scope gives a reauthorization hint`() = runBlocking {
        val esi = EsiMock {
            characterAssets(123).willReturn(
                """{"error":"token is not valid for this scope"}""",
                HttpStatusCode.Forbidden,
            )
        }
        val source = EveAssetSource(esi.client, esi.baseUrl)
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character))

        assertEquals("Bearer access", esi.requests.single().headers[HttpHeaders.Authorization])
        assertThat(result.failures.single().message).containsIgnoringCase("re-authorize")
        Unit
    }

    @Test
    fun `custom container names are shown and searchable in the asset path`() = runBlocking {
        val esi = EsiMock {
            characterAssets(123).willReturn(
                """[{"item_id":101,"type_id":100,"quantity":1,"location_id":60000001,"location_type":"station"},{"item_id":102,"type_id":34,"quantity":42,"location_id":101,"location_type":"item"}]""",
            )
            universeNames().willReturn("""[{"id":100,"name":"Secure Container"},{"id":34,"name":"Tritanium"}]""")
            characterAssetNames(123).willReturn("""[{"item_id":101,"name":"Mining supplies"}]""")
            universeStations(60000001).willReturn("""{"name":"Jita"}""")
        }
        val source = EveAssetSource(esi.client, esi.baseUrl)
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character), "sUpPLiEs")

        assertEquals("Mining supplies", result.items.single().containerPath)
        assertEquals("Jita", result.items.single().stationName)
        assertEquals("Tritanium", result.items.single().typeName)
    }

    @Test
    fun `an unknown type id does not hide other asset names or rows`() = runBlocking {
        val unknownType = 2147483647L
        val esi = EsiMock {
            characterAssets(123).willReturn(
                """[{"item_id":101,"type_id":34,"quantity":1,"location_id":60000001,"location_type":"station"},{"item_id":102,"type_id":$unknownType,"quantity":2,"location_id":60000001,"location_type":"station"}]""",
            )
            universeNames().willRespond { request ->
                val ids = Json.decodeFromString<List<Long>>(request.body)
                if (unknownType in ids) {
                    EsiMock.EsiResponse("""{"error":"Ensure all IDs are valid before resolving."}""", HttpStatusCode.NotFound)
                } else {
                    EsiMock.EsiResponse("""[{"id":34,"name":"Tritanium"}]""")
                }
            }
            characterAssetNames(123).willReturn("[]")
            universeStations(60000001).willReturn("""{"name":"Jita"}""")
        }
        val source = EveAssetSource(esi.client, esi.baseUrl)
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character))

        assertNoFailures(result)
        assertEquals(setOf("Tritanium", "Unknown type ($unknownType)"), result.items.map { it.typeName }.toSet())
    }

    @Test
    fun `an unavailable type name service leaves assets visible by id`() = runBlocking {
        val esi = EsiMock {
            characterAssets(123).willReturn(
                """[{"item_id":101,"type_id":34,"quantity":7,"location_id":60000001,"location_type":"station"}]""",
            )
            universeNames().willReturn("unavailable", HttpStatusCode.ServiceUnavailable)
            characterAssetNames(123).willReturn("[]")
            universeStations(60000001).willReturn("""{"name":"Jita"}""")
        }
        val source = EveAssetSource(esi.client, esi.baseUrl)
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character))

        assertNoFailures(result)
        assertEquals("Unknown type (34)", result.items.single().typeName)
        assertEquals(7, result.items.single().quantity)
    }

    @Test
    fun `unavailable station and structure names leave assets visible by location id`() = runBlocking {
        val structureId = 1_000_000_000_001L
        val esi = EsiMock {
            characterAssets(123).willReturn(
                """[{"item_id":101,"type_id":34,"quantity":1,"location_id":60000001,"location_type":"station"},{"item_id":102,"type_id":34,"quantity":2,"location_id":$structureId,"location_type":"other"}]""",
            )
            universeNames().willReturn("""[{"id":34,"name":"Tritanium"}]""")
            characterAssetNames(123).willReturn("[]")
            universeStations(60000001).willReturn("unavailable", HttpStatusCode.ServiceUnavailable)
            universeStructures(structureId).willReturn("unavailable", HttpStatusCode.ServiceUnavailable)
        }
        val source = EveAssetSource(esi.client, esi.baseUrl)
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character))

        assertNoFailures(result)
        assertEquals(setOf("Location 60000001", "Location $structureId"), result.items.map { it.stationName }.toSet())
    }

    @Test
    fun `unavailable container names fall back to the container type`() = runBlocking {
        val esi = EsiMock {
            characterAssets(123).willReturn(
                """[{"item_id":101,"type_id":100,"quantity":1,"location_id":60000001,"location_type":"station"},{"item_id":102,"type_id":34,"quantity":42,"location_id":101,"location_type":"item"}]""",
            )
            universeNames().willReturn("""[{"id":100,"name":"Secure Container"},{"id":34,"name":"Tritanium"}]""")
            characterAssetNames(123).willReturn("unavailable", HttpStatusCode.ServiceUnavailable)
            universeStations(60000001).willReturn("""{"name":"Jita"}""")
        }
        val source = EveAssetSource(esi.client, esi.baseUrl)
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character))

        assertNoFailures(result)
        assertEquals("Secure Container", result.items.single { it.typeName == "Tritanium" }.containerPath)
    }

    @Test
    fun `connection failures in name lookups preserve fetched assets`() = runBlocking {
        val esi = EsiMock {
            characterAssets(123).willReturn(
                """[{"item_id":101,"type_id":100,"quantity":1,"location_id":60000001,"location_type":"station"},{"item_id":102,"type_id":34,"quantity":42,"location_id":101,"location_type":"item"}]""",
            )
            universeNames().willFail(IOException("lookup unavailable"))
            characterAssetNames(123).willFail(IOException("lookup unavailable"))
            universeStations(60000001).willFail(IOException("lookup unavailable"))
        }
        val source = EveAssetSource(esi.client, esi.baseUrl)
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character))

        assertNoFailures(result)
        val item = result.items.single { it.typeName == "Unknown type (34)" }
        assertEquals("Location 60000001", item.stationName)
        assertEquals("Unknown type (100)", item.containerPath)
        assertEquals(42, item.quantity)
    }

    @Test
    fun `repeated name lookup not found responses are bounded`() = runBlocking {
        val assets = (1..100).joinToString(prefix = "[", postfix = "]") { index ->
            """{"item_id":${1000 + index},"type_id":${10000 + index},"quantity":1,"location_id":60000001,"location_type":"station"}"""
        }
        val esi = EsiMock {
            characterAssets(123).willReturn(assets)
            universeNames().willReturn("not found", HttpStatusCode.NotFound)
            characterAssetNames(123).willReturn("[]")
            universeStations(60000001).willReturn("""{"name":"Jita"}""")
        }
        val source = EveAssetSource(esi.client, esi.baseUrl)
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character))

        assertEquals(100, result.items.size)
        assertNoFailures(result)
        val nameRequests = esi.requests.count { it.path.endsWith("/universe/names/") }
        assertTrue(nameRequests <= 8, "Name lookup requests: $nameRequests")
    }

    private class MemoryAssetCache(initialPage: CachedAssetPage? = null) : AssetCache {
        private val snapshots = mutableMapOf<Long, AssetSnapshot>()
        private val pages = mutableMapOf<Pair<Long, Int>, CachedAssetPage>()

        init {
            initialPage?.let { pages[123L to it.page] = it }
        }

        override fun findSnapshot(characterId: Long): AssetSnapshot? = snapshots[characterId]

        override fun findPage(characterId: Long, page: Int): CachedAssetPage? = pages[characterId to page]

        override fun replace(characterId: Long, snapshot: AssetSnapshot, pages: List<CachedAssetPage>) {
            snapshots[characterId] = snapshot
            this.pages.keys.removeAll { it.first == characterId }
            pages.forEach { this.pages[characterId to it.page] = it }
        }
    }

    private fun assertNoFailures(result: AssetListing) {
        assertThat(result.failures).isEmpty()
    }
}
package eve.hq.data

import eve.hq.core.asset.ListAssets
import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import eve.hq.data.esi.EveAssetSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EveAssetSourceTest {
    @Test
    fun `every asset page is loaded`() = runBlocking {
        val pages = mutableListOf<String>()
        val engine = MockEngine { request ->
            val response = when {
                request.url.encodedPath.endsWith("/characters/123/assets/") -> {
                    val page = request.url.parameters["page"].orEmpty()
                    pages += page
                    val itemId = if (page == "1") 101 else 102
                    respond(
                        """[{"item_id":$itemId,"type_id":34,"quantity":2,"location_id":60000001,"location_type":"station"}]""",
                        HttpStatusCode.OK,
                        headersOf(
                            HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                            "X-Pages" to listOf("2"),
                        ),
                    )
                }
                request.url.encodedPath.endsWith("/universe/names/") -> respond(
                    """[{"category":"inventory_type","id":34,"name":"Tritanium"}]""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/characters/123/assets/names/") -> respond(
                    "[]",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/universe/stations/60000001/") -> respond(
                    """{"name":"Jita IV - Moon 4 - Caldari Navy Assembly Plant"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> error("Unexpected request: ${request.url}")
            }
            response
        }
        val source = EveAssetSource(HttpClient(engine), "https://esi.test/latest/")
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val inventory = source.load(character)

        assertEquals(listOf("1", "2"), pages)
        assertEquals(listOf(101L, 102L), inventory.items.map { it.itemId })
    }

    @Test
    fun `type and asset name lookups are split into batches of at most one thousand`() = runBlocking {
        val typeBatchSizes = mutableListOf<Int>()
        val itemBatchSizes = mutableListOf<Int>()
        val assets = (0..1000).joinToString(prefix = "[", postfix = "]") { index ->
            """{"item_id":${100000L + index},"type_id":${10000L + index},"quantity":1,"location_id":60000001,"location_type":"station"}"""
        }
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/characters/123/assets/") -> respond(
                    assets,
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                        "X-Pages" to listOf("1"),
                    ),
                )
                request.url.encodedPath.endsWith("/universe/names/") -> {
                    val ids = Json.decodeFromString<List<Long>>(
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
                    )
                    typeBatchSizes += ids.size
                    respond(
                        ids.joinToString(prefix = "[", postfix = "]") { id ->
                            """{"category":"inventory_type","id":$id,"name":"Type $id"}"""
                        },
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                request.url.encodedPath.endsWith("/characters/123/assets/names/") -> {
                    val ids = Json.decodeFromString<List<Long>>(
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
                    )
                    itemBatchSizes += ids.size
                    respond(
                        "[]",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                request.url.encodedPath.endsWith("/universe/stations/60000001/") -> respond(
                    """{"name":"Jita"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val source = EveAssetSource(HttpClient(engine), "https://esi.test/latest/")
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val inventory = source.load(character)

        assertEquals(listOf(1000, 1), typeBatchSizes)
        assertEquals(listOf(1000, 1), itemBatchSizes)
        assertEquals(1001, inventory.typeNames.size)
    }

    @Test
    fun `an inaccessible structure is left for the caller to display as an id fallback`() = runBlocking {
        val structureId = 1_000_000_000_001L
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/characters/123/assets/") -> respond(
                    """[{"item_id":101,"type_id":34,"quantity":1,"location_id":$structureId,"location_type":"other"}]""",
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                        "X-Pages" to listOf("1"),
                    ),
                )
                request.url.encodedPath.endsWith("/universe/names/") -> respond(
                    "[]",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/characters/123/assets/names/") -> respond(
                    "[]",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/universe/structures/$structureId/") -> respond(
                    """{"error":"Forbidden"}""",
                    HttpStatusCode.Forbidden,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val source = EveAssetSource(HttpClient(engine), "https://esi.test/latest/")
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val inventory = source.load(character)

        assertEquals(1, inventory.items.size)
        assertFalse(structureId in inventory.locationNames)
    }

    @Test
    fun `missing asset scope gives a reauthorization hint`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
            respond(
                """{"error":"token is not valid for this scope"}""",
                HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val source = EveAssetSource(HttpClient(engine), "https://esi.test/latest/")
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character))

        assertTrue(result.failures.single().message.contains("re-authorize", ignoreCase = true))
    }

    @Test
    fun `custom container names are shown and searchable in the asset path`() = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/characters/123/assets/") -> respond(
                    """[{"item_id":101,"type_id":100,"quantity":1,"location_id":60000001,"location_type":"station"},{"item_id":102,"type_id":34,"quantity":42,"location_id":101,"location_type":"item"}]""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/universe/names/") -> respond(
                    """[{"id":100,"name":"Secure Container"},{"id":34,"name":"Tritanium"}]""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/characters/123/assets/names/") -> respond(
                    """[{"item_id":101,"name":"Mining supplies"}]""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/universe/stations/60000001/") -> respond(
                    """{"name":"Jita"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val source = EveAssetSource(HttpClient(engine), "https://esi.test/latest/")
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character), "sUpPLiEs")

        assertEquals("Mining supplies", result.items.single().containerPath)
        assertEquals("Jita", result.items.single().stationName)
        assertEquals("Tritanium", result.items.single().typeName)
    }

    @Test
    fun `an unknown type id does not hide other asset names or rows`() = runBlocking {
        val unknownType = 2147483647L
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/characters/123/assets/") -> respond(
                    """[{"item_id":101,"type_id":34,"quantity":1,"location_id":60000001,"location_type":"station"},{"item_id":102,"type_id":$unknownType,"quantity":2,"location_id":60000001,"location_type":"station"}]""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith("/universe/names/") -> {
                    val ids = Json.decodeFromString<List<Long>>(
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
                    )
                    if (unknownType in ids) {
                        respond("""{"error":"Ensure all IDs are valid before resolving."}""", HttpStatusCode.NotFound)
                    } else {
                        respond("""[{"id":34,"name":"Tritanium"}]""", HttpStatusCode.OK)
                    }
                }
                request.url.encodedPath.endsWith("/characters/123/assets/names/") -> respond("[]", HttpStatusCode.OK)
                request.url.encodedPath.endsWith("/universe/stations/60000001/") -> respond("""{"name":"Jita"}""", HttpStatusCode.OK)
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val source = EveAssetSource(HttpClient(engine), "https://esi.test/latest/")
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character))

        assertEquals(emptyList(), result.failures)
        assertEquals(setOf("Tritanium", "Unknown type ($unknownType)"), result.items.map { it.typeName }.toSet())
    }

    @Test
    fun `an unavailable type name service leaves assets visible by id`() = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/characters/123/assets/") -> respond(
                    """[{"item_id":101,"type_id":34,"quantity":7,"location_id":60000001,"location_type":"station"}]""",
                    HttpStatusCode.OK,
                )
                request.url.encodedPath.endsWith("/universe/names/") -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
                request.url.encodedPath.endsWith("/characters/123/assets/names/") -> respond("[]", HttpStatusCode.OK)
                request.url.encodedPath.endsWith("/universe/stations/60000001/") -> respond("""{"name":"Jita"}""", HttpStatusCode.OK)
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val source = EveAssetSource(HttpClient(engine), "https://esi.test/latest/")
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character))

        assertEquals(emptyList(), result.failures)
        assertEquals("Unknown type (34)", result.items.single().typeName)
        assertEquals(7, result.items.single().quantity)
    }

    @Test
    fun `unavailable station and structure names leave assets visible by location id`() = runBlocking {
        val structureId = 1_000_000_000_001L
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/characters/123/assets/") -> respond(
                    """[{"item_id":101,"type_id":34,"quantity":1,"location_id":60000001,"location_type":"station"},{"item_id":102,"type_id":34,"quantity":2,"location_id":$structureId,"location_type":"other"}]""",
                    HttpStatusCode.OK,
                )
                request.url.encodedPath.endsWith("/universe/names/") -> respond("""[{"id":34,"name":"Tritanium"}]""", HttpStatusCode.OK)
                request.url.encodedPath.endsWith("/characters/123/assets/names/") -> respond("[]", HttpStatusCode.OK)
                request.url.encodedPath.endsWith("/universe/stations/60000001/") -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
                request.url.encodedPath.endsWith("/universe/structures/$structureId/") -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val source = EveAssetSource(HttpClient(engine), "https://esi.test/latest/")
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character))

        assertEquals(emptyList(), result.failures)
        assertEquals(setOf("Location 60000001", "Location $structureId"), result.items.map { it.stationName }.toSet())
    }

    @Test
    fun `unavailable container names fall back to the container type`() = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/characters/123/assets/") -> respond(
                    """[{"item_id":101,"type_id":100,"quantity":1,"location_id":60000001,"location_type":"station"},{"item_id":102,"type_id":34,"quantity":42,"location_id":101,"location_type":"item"}]""",
                    HttpStatusCode.OK,
                )
                request.url.encodedPath.endsWith("/universe/names/") -> respond(
                    """[{"id":100,"name":"Secure Container"},{"id":34,"name":"Tritanium"}]""",
                    HttpStatusCode.OK,
                )
                request.url.encodedPath.endsWith("/characters/123/assets/names/") -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
                request.url.encodedPath.endsWith("/universe/stations/60000001/") -> respond("""{"name":"Jita"}""", HttpStatusCode.OK)
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val source = EveAssetSource(HttpClient(engine), "https://esi.test/latest/")
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character))

        assertEquals(emptyList(), result.failures)
        assertEquals("Secure Container", result.items.single { it.typeName == "Tritanium" }.containerPath)
    }

    @Test
    fun `connection failures in name lookups preserve fetched assets`() = runBlocking {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/characters/123/assets/")) {
                respond(
                    """[{"item_id":101,"type_id":100,"quantity":1,"location_id":60000001,"location_type":"station"},{"item_id":102,"type_id":34,"quantity":42,"location_id":101,"location_type":"item"}]""",
                    HttpStatusCode.OK,
                )
            } else {
                throw IOException("lookup unavailable")
            }
        }
        val source = EveAssetSource(HttpClient(engine), "https://esi.test/latest/")
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character))

        assertEquals(emptyList(), result.failures)
        val item = result.items.single { it.typeName == "Unknown type (34)" }
        assertEquals("Location 60000001", item.stationName)
        assertEquals("Unknown type (100)", item.containerPath)
        assertEquals(42, item.quantity)
    }

    @Test
    fun `repeated name lookup not found responses are bounded`() = runBlocking {
        var nameRequests = 0
        val assets = (1..100).joinToString(prefix = "[", postfix = "]") { index ->
            """{"item_id":${1000 + index},"type_id":${10000 + index},"quantity":1,"location_id":60000001,"location_type":"station"}"""
        }
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/characters/123/assets/") -> respond(assets, HttpStatusCode.OK)
                request.url.encodedPath.endsWith("/universe/names/") -> {
                    nameRequests++
                    respond("not found", HttpStatusCode.NotFound)
                }
                request.url.encodedPath.endsWith("/characters/123/assets/names/") -> respond("[]", HttpStatusCode.OK)
                request.url.encodedPath.endsWith("/universe/stations/60000001/") -> respond("""{"name":"Jita"}""", HttpStatusCode.OK)
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val source = EveAssetSource(HttpClient(engine), "https://esi.test/latest/")
        val character = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

        val result = ListAssets(source)(listOf(character))

        assertEquals(100, result.items.size)
        assertEquals(emptyList(), result.failures)
        assertTrue(nameRequests <= 8, "Name lookup requests: $nameRequests")
    }
}
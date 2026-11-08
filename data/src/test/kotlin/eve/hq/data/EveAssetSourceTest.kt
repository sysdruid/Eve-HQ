package eve.hq.data

import eve.hq.core.asset.AssetItem
import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetType
import eve.hq.core.asset.ListAssets
import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import eve.hq.data.esi.EveAssetSource
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EveAssetSourceTest {
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

        assertEquals(
            listOf("1", "2"),
            esi.requests.filter { it.path.endsWith("/characters/123/assets/") }.map { it.queryParameters["page"] },
        )
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
        assertTrue(result.failures.single().message.contains("re-authorize", ignoreCase = true))
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

        assertEquals(emptyList(), result.failures)
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

        assertEquals(emptyList(), result.failures)
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

        assertEquals(emptyList(), result.failures)
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

        assertEquals(emptyList(), result.failures)
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

        assertEquals(emptyList(), result.failures)
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
        assertEquals(emptyList(), result.failures)
        val nameRequests = esi.requests.count { it.path.endsWith("/universe/names/") }
        assertTrue(nameRequests <= 8, "Name lookup requests: $nameRequests")
    }
}
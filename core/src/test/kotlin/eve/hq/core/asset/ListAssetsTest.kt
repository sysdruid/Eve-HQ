package eve.hq.core.asset

import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ListAssetsTest {
    @Test
    fun `given no characters, the asset list is empty`() = runBlocking {
        val source = AssetSource { error("asset source must not be called") }

        val result = ListAssets(source)(emptyList())

        assertEquals(AssetListing(emptyList(), emptyList()), result)
    }

    @Test
    fun `an asset row includes the character station container type and quantity`() = runBlocking {
        val character = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
        val source = AssetSource {
            AssetInventory(
                items = listOf(
                    AssetItem(itemId = 700, typeId = 100, quantity = 1, locationId = 600),
                    AssetItem(itemId = 701, typeId = 34, quantity = 42, locationId = 700),
                ),
                typeNames = mapOf(100L to "Small Secure Container", 34L to "Tritanium"),
                locationNames = mapOf(600L to "Jita IV - Moon 4 - Caldari Navy Assembly Plant"),
            )
        }

        val result = ListAssets(source)(listOf(character))

        assertEquals(
            AssetListing(
                items = listOf(
                    AssetRow(
                        characterName = "Alice",
                        stationName = "Jita IV - Moon 4 - Caldari Navy Assembly Plant",
                        containerPath = "",
                        typeName = "Small Secure Container",
                        quantity = 1,
                    ),
                    AssetRow(
                        characterName = "Alice",
                        stationName = "Jita IV - Moon 4 - Caldari Navy Assembly Plant",
                        containerPath = "Small Secure Container",
                        typeName = "Tritanium",
                        quantity = 42,
                    ),
                ),
                failures = emptyList(),
            ),
            result,
        )
    }

    @Test
    fun `nested containers are listed from the station inward`() = runBlocking {
        val character = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
        val source = AssetSource {
            AssetInventory(
                items = listOf(
                    AssetItem(itemId = 700, typeId = 100, quantity = 1, locationId = 600),
                    AssetItem(itemId = 701, typeId = 200, quantity = 1, locationId = 700),
                    AssetItem(itemId = 702, typeId = 300, quantity = 12, locationId = 701),
                ),
                typeNames = mapOf(100L to "Large Container", 200L to "Small Container", 300L to "Tritanium"),
                locationNames = mapOf(600L to "Jita Station"),
            )
        }

        val result = ListAssets(source)(listOf(character))

        assertEquals("Large Container > Small Container", result.items.single { it.typeName == "Tritanium" }.containerPath)
    }

    @Test
    fun `cyclic container parents terminate and leave assets visible`() = runBlocking {
        val character = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
        val source = AssetSource {
            AssetInventory(
                items = listOf(
                    AssetItem(itemId = 700, typeId = 100, quantity = 1, locationId = 701),
                    AssetItem(itemId = 701, typeId = 200, quantity = 1, locationId = 700),
                ),
                typeNames = mapOf(100L to "Container A", 200L to "Container B"),
                locationNames = emptyMap(),
            )
        }

        val result = ListAssets(source)(listOf(character))

        assertEquals(2, result.items.size)
        assertEquals(setOf("Location 700", "Location 701"), result.items.map { it.stationName }.toSet())
    }

    @Test
    fun `missing stations and parent containers use location ids without hiding assets`() = runBlocking {
        val character = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
        val source = AssetSource {
            AssetInventory(
                items = listOf(
                    AssetItem(itemId = 700, typeId = 100, quantity = 1, locationId = 600),
                    AssetItem(itemId = 701, typeId = 200, quantity = 4, locationId = 500),
                ),
                typeNames = mapOf(100L to "Container", 200L to "Tritanium"),
                locationNames = emptyMap(),
            )
        }

        val result = ListAssets(source)(listOf(character))

        assertEquals(2, result.items.size)
        assertEquals(setOf("Location 500", "Location 600"), result.items.map { it.stationName }.toSet())
        assertEquals(4, result.items.single { it.typeName == "Tritanium" }.quantity)
    }

    @Test
    fun `search matches item and station substrings without case sensitivity`() = runBlocking {
        val character = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
        val source = AssetSource {
            AssetInventory(
                items = listOf(
                    AssetItem(itemId = 700, typeId = 100, quantity = 1, locationId = 600),
                    AssetItem(itemId = 701, typeId = 34, quantity = 42, locationId = 700),
                ),
                typeNames = mapOf(100L to "Container", 34L to "Tritanium"),
                locationNames = mapOf(600L to "Jita Station"),
            )
        }

        val byType = ListAssets(source)(listOf(character), "tRiTa").items
        val byStation = ListAssets(source)(listOf(character), "jItA sTa").items

        assertEquals(listOf("Tritanium"), byType.map { it.typeName })
        assertEquals(listOf("Container", "Tritanium"), byStation.map { it.typeName })
    }

    @Test
    fun `assets from multiple characters keep their character ownership`() = runBlocking {
        val alice = EveCharacter(1, "Alice", AuthToken("alice", "refresh", Instant.MAX))
        val bob = EveCharacter(2, "Bob", AuthToken("bob", "refresh", Instant.MAX))
        val source = AssetSource { character ->
            AssetInventory(
                items = listOf(AssetItem(itemId = character.id, typeId = 34, quantity = 1, locationId = 600)),
                typeNames = mapOf(34L to "Tritanium"),
                locationNames = mapOf(600L to "Jita Station"),
            )
        }

        val result = ListAssets(source)(listOf(bob, alice))

        assertEquals(listOf("Alice", "Bob"), result.items.map { it.characterName })
    }

    @Test
    fun `a character failure is reported without hiding other characters assets`() = runBlocking {
        val alice = EveCharacter(1, "Alice", AuthToken("alice", "refresh", Instant.MAX))
        val bob = EveCharacter(2, "Bob", AuthToken("bob", "refresh", Instant.MAX))
        val source = AssetSource { character ->
            if (character.id == alice.id) throw IOException("ESI unavailable")
            AssetInventory(
                items = listOf(AssetItem(itemId = 702, typeId = 34, quantity = 5, locationId = 600)),
                typeNames = mapOf(34L to "Tritanium"),
                locationNames = mapOf(600L to "Jita Station"),
            )
        }

        val result = ListAssets(source)(listOf(alice, bob))

        assertEquals(listOf("Bob"), result.items.map { it.characterName })
        assertEquals(listOf(AssetLoadFailure(1, "Alice", "ESI unavailable")), result.failures)
    }
}
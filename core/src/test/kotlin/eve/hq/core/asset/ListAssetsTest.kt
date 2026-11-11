package eve.hq.core.asset

import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ListAssetsTest {
    private val loadedAt = Instant.parse("2026-11-11T12:00:00Z")

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
            listOf(
                assetItem(700, 100, "Small Secure Container", 1, 600, "Jita IV - Moon 4 - Caldari Navy Assembly Plant"),
                assetItem(701, 34, "Tritanium", 42, 700),
            )
        }

        val result = ListAssets(source, Clock.fixed(loadedAt, ZoneOffset.UTC))(listOf(character))

        assertEquals(
            AssetListing(
                items = listOf(
                    AssetRow(
                        characterName = "Alice",
                        stationName = "Jita IV - Moon 4 - Caldari Navy Assembly Plant",
                        containerPath = "",
                        typeName = "Small Secure Container",
                        quantity = 1,
                        typeId = 100,
                    ),
                    AssetRow(
                        characterName = "Alice",
                        stationName = "Jita IV - Moon 4 - Caldari Navy Assembly Plant",
                        containerPath = "Small Secure Container",
                        typeName = "Tritanium",
                        quantity = 42,
                        typeId = 34,
                    ),
                ),
                failures = emptyList(),
                snapshots = listOf(AssetSnapshotInfo(1, "Alice", loadedAt)),
            ),
            result,
        )
    }

    @Test
    fun `nested containers are listed from the station inward`() = runBlocking {
        val character = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
        val source = AssetSource {
            listOf(
                assetItem(700, 100, "Large Container", 1, 600, "Jita Station"),
                assetItem(701, 200, "Small Container", 1, 700),
                assetItem(702, 300, "Tritanium", 12, 701),
            )
        }

        val result = ListAssets(source)(listOf(character))

        assertEquals("Large Container > Small Container", result.items.single { it.typeName == "Tritanium" }.containerPath)
    }

    @Test
    fun `cyclic container parents terminate and leave assets visible`() = runBlocking {
        val character = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
        val source = AssetSource {
            listOf(
                assetItem(700, 100, "Container A", 1, 701),
                assetItem(701, 200, "Container B", 1, 700),
            )
        }

        val result = ListAssets(source)(listOf(character))

        assertThat(result.items.map { it.stationName })
            .containsExactlyInAnyOrder("Location 700", "Location 701")
        Unit
    }

    @Test
    fun `missing stations and parent containers use location ids without hiding assets`() = runBlocking {
        val character = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
        val source = AssetSource {
            listOf(
                assetItem(700, 100, "Container", 1, 600),
                assetItem(701, 200, "Tritanium", 4, 500),
            )
        }

        val result = ListAssets(source)(listOf(character))

        assertThat(result.items.map { it.stationName })
            .containsExactlyInAnyOrder("Location 500", "Location 600")
        assertEquals(4, result.items.single { it.typeName == "Tritanium" }.quantity)
    }

    @Test
    fun `search matches item and station substrings without case sensitivity`() = runBlocking {
        val character = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
        val source = AssetSource {
            listOf(
                assetItem(700, 100, "Container", 1, 600, "Jita Station"),
                assetItem(701, 34, "Tritanium", 42, 700),
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
            listOf(assetItem(character.id, 34, "Tritanium", 1, 600, "Jita Station"))
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
            listOf(assetItem(702, 34, "Tritanium", 5, 600, "Jita Station"))
        }

        val result = ListAssets(source)(listOf(alice, bob))

        assertEquals(listOf("Bob"), result.items.map { it.characterName })
        assertEquals(listOf(AssetLoadFailure(1, "Alice", "ESI unavailable")), result.failures)
    }

    @Test
    fun `a failed refresh returns the saved snapshot alongside its failure`() = runBlocking {
        val character = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
        val cached = AssetSnapshot(
            listOf(assetItem(701, 34, "Tritanium", 42, 600, "Jita Station")),
            Instant.parse("2026-11-08T12:00:00Z"),
        )
        val source = object : AssetSource {
            override suspend fun load(character: EveCharacter): List<AssetItem> = error("ESI unavailable")
            override fun cachedSnapshot(characterId: Long): AssetSnapshot = cached
        }

        val result = ListAssets(source)(listOf(character))

        assertEquals(listOf("Tritanium"), result.items.map { it.typeName })
        assertEquals(listOf(AssetLoadFailure(1, "Alice", "ESI unavailable")), result.failures)
    }

    @Test
    fun `cancellation while loading a character is propagated`() = runBlocking<Unit> {
        val character = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
        val source = AssetSource { throw CancellationException("cancelled") }

        assertFailsWith<CancellationException> {
            ListAssets(source)(listOf(character))
        }
    }

    @Test
    fun `a failure without a message uses the default message`() = runBlocking {
        val character = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
        val source = AssetSource { throw IOException() }

        val result = ListAssets(source)(listOf(character))

        assertEquals(listOf(AssetLoadFailure(1, "Alice", "Unable to load assets")), result.failures)
    }

    @Test
    fun `custom container names and unknown types use their respective labels`() = runBlocking {
        val character = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
        val source = AssetSource {
            listOf(
                assetItem(700, 100, null, 1, 600, "Jita Station", customName = "Hangar Box"),
                assetItem(701, 999, null, 3, 700),
            )
        }

        val result = ListAssets(source)(listOf(character))

        assertEquals("Hangar Box", result.items.single { it.quantity == 3 }.containerPath)
        assertEquals("Unknown type (999)", result.items.single { it.quantity == 3 }.typeName)
        assertEquals("Unknown type (100)", result.items.single { it.quantity == 1 }.typeName)
    }

    private fun assetItem(
        itemId: Long,
        typeId: Long,
        typeName: String?,
        quantity: Int,
        locationId: Long,
        locationName: String? = null,
        customName: String? = null,
    ) = AssetItem(
        itemId,
        AssetType(typeId, typeName),
        customName,
        quantity,
        AssetLocation(locationId, locationName),
    )
}
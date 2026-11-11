package eve.hq.features

import eve.hq.core.asset.AssetRow
import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import eve.hq.data.EsiMock
import eve.hq.features.support.FeatureSystem
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ListAssetsFeatureTest {
    private val system = FeatureSystem()

    @Test
    fun `a character without cached assets gets an ESI listing and a persisted snapshot`() {
        val body = """[{"item_id":101,"type_id":34,"quantity":7,"location_id":60000001,"location_type":"station"}]"""
        system.givenCharacters(1L to "Alice")
        val initialCache = system.cachedAssets()
        system.esi.apply {
            characterAssets(1).willReturnAssetPage(body, "\"alice-assets-v1\"", FUTURE_EXPIRY)
            willResolveAssetNames(listOf(1), mapOf(34L to "Tritanium"), mapOf(60000001L to "Jita"))
        }

        val result = runBlocking { system.listAssets() }
        val cached = system.cachedAssets()

        assertThat(initialCache.items).isEmpty()
        assertThat(initialCache.snapshots).isEmpty()
        assertThat(result.failures).isEmpty()
        assertThat(result.items).containsExactly(AssetRow("Alice", "Jita", "", "Tritanium", 7, 34))
        assertThat(cached.items).containsExactlyElementsOf(result.items)
        assertThat(cached.snapshots.single().updatedAt).isEqualTo(system.now)
        system.esi.characterAssets(1).assertPagesRequested(1)
    }

    @Test
    fun `a reopened asset service reads the saved inventory without refetching fresh pages`() {
        val body = """[{"item_id":101,"type_id":34,"quantity":7,"location_id":60000001,"location_type":"station"}]"""
        system.givenCharacters(1L to "Alice")
        system.esi.apply {
            characterAssets(1).willReturnAssetPage(body, "\"alice-assets-v1\"", FUTURE_EXPIRY)
            willResolveAssetNames(listOf(1), mapOf(34L to "Tritanium"), mapOf(60000001L to "Jita"))
        }
        runBlocking { system.listAssets() }

        system.reopenAssetServices()
        val cached = system.cachedAssets()
        val refreshed = runBlocking { system.listAssets() }

        assertThat(cached.items).containsExactly(AssetRow("Alice", "Jita", "", "Tritanium", 7, 34))
        assertThat(cached.snapshots.single().updatedAt).isEqualTo(system.now)
        assertThat(refreshed.items).containsExactlyElementsOf(cached.items)
        assertThat(refreshed.failures).isEmpty()
        system.esi.characterAssets(1).assertPagesRequested(1)
    }

    @Test
    fun `only an expired page is revalidated with its own etag and saved body`() {
        val firstPage = """[{"item_id":101,"type_id":34,"quantity":7,"location_id":60000001,"location_type":"station"}]"""
        val secondPage = """[{"item_id":102,"type_id":35,"quantity":3,"location_id":60000001,"location_type":"station"}]"""
        system.givenCharacters(1L to "Alice")
        var pageTwoRequests = 0
        system.esi.apply {
            characterAssets(1).willReturnAssetPage(firstPage, "\"alice-page-1\"", FUTURE_EXPIRY, pageCount = 2)
            characterAssets(1).page(2).willRespond { request ->
                pageTwoRequests++
                if (pageTwoRequests == 1) {
                    EsiMock.EsiResponse(
                        body = secondPage,
                        headers = mapOf(
                            HttpHeaders.ETag to "\"alice-page-2\"",
                            HttpHeaders.Expires to PAST_EXPIRY,
                            "X-Pages" to "2",
                        ),
                    )
                } else {
                    assertThat(request.headers[HttpHeaders.IfNoneMatch]).isEqualTo("\"alice-page-2\"")
                    EsiMock.EsiResponse(
                        body = "",
                        status = HttpStatusCode.NotModified,
                        headers = mapOf(HttpHeaders.Expires to FUTURE_EXPIRY),
                    )
                }
            }
            willResolveAssetNames(
                listOf(1),
                mapOf(34L to "Tritanium", 35L to "Pyerite"),
                mapOf(60000001L to "Jita"),
            )
        }
        runBlocking { system.listAssets() }

        system.reopenAssetServices()
        val result = runBlocking { system.listAssets() }

        assertThat(result.failures).isEmpty()
        assertThat(result.items).containsExactlyInAnyOrder(
            AssetRow("Alice", "Jita", "", "Tritanium", 7, 34),
            AssetRow("Alice", "Jita", "", "Pyerite", 3, 35),
        )
        system.esi.characterAssets(1).assertPagesRequested(1, 2, 2)
    }

    @Test
    fun `a page-count shrink persists an empty inventory and discards obsolete pages`() {
        val firstPage = """[{"item_id":101,"type_id":34,"quantity":7,"location_id":60000001,"location_type":"station"}]"""
        val oldSecondPage = """[{"item_id":102,"type_id":35,"quantity":3,"location_id":60000001,"location_type":"station"}]"""
        val newFirstPage = """[{"item_id":103,"type_id":36,"quantity":2,"location_id":60000001,"location_type":"station"}]"""
        val newSecondPage = """[{"item_id":104,"type_id":37,"quantity":1,"location_id":60000001,"location_type":"station"}]"""
        system.givenCharacters(1L to "Alice")
        var firstPageRequests = 0
        var secondPageRequests = 0
        system.esi.apply {
            characterAssets(1).willRespond {
                firstPageRequests++
                when (firstPageRequests) {
                    1 -> assetPageResponse(firstPage, "\"alice-page-1\"", PAST_EXPIRY, 2)
                    2 -> assetPageResponse("[]", "\"alice-empty\"", PAST_EXPIRY, 1)
                    else -> assetPageResponse(newFirstPage, "\"alice-expanded\"", FUTURE_EXPIRY, 2)
                }
            }
            characterAssets(1).page(2).willRespond {
                secondPageRequests++
                val body = if (secondPageRequests == 1) oldSecondPage else newSecondPage
                val etag = if (secondPageRequests == 1) "\"alice-old-page-2\"" else "\"alice-new-page-2\""
                assetPageResponse(body, etag, FUTURE_EXPIRY, 2)
            }
            willResolveAssetNames(
                listOf(1),
                mapOf(34L to "Tritanium", 35L to "Pyerite", 36L to "Mexallon", 37L to "Isogen"),
                mapOf(60000001L to "Jita"),
            )
        }
        val initial = runBlocking { system.listAssets() }

        system.reopenAssetServices()
        val empty = runBlocking { system.listAssets() }
        val savedEmpty = system.cachedAssets()
        val expanded = runBlocking { system.listAssets() }

        assertThat(initial.failures).isEmpty()
        assertThat(initial.items.map(AssetRow::typeName)).containsExactlyInAnyOrder("Tritanium", "Pyerite")
        assertThat(empty.failures).isEmpty()
        assertThat(empty.items).isEmpty()
        assertThat(savedEmpty.items).isEmpty()
        assertThat(savedEmpty.snapshots).hasSize(1)
        assertThat(expanded.items.map(AssetRow::typeName)).containsExactlyInAnyOrder("Mexallon", "Isogen")
        system.esi.characterAssets(1).assertPagesRequested(1, 2, 1, 1, 2)
    }

    @Test
    fun `a failed database replacement keeps the old listing and page validator`() {
        val savedBody = """[{"item_id":101,"type_id":34,"quantity":7,"location_id":60000001,"location_type":"station"}]"""
        val duplicateItems = """[
            {"item_id":102,"type_id":34,"quantity":1,"location_id":60000001,"location_type":"station"},
            {"item_id":102,"type_id":35,"quantity":2,"location_id":60000001,"location_type":"station"}
        ]"""
        system.givenCharacters(1L to "Alice")
        var requests = 0
        system.esi.apply {
            characterAssets(1).willRespond { request ->
                requests++
                when (requests) {
                    1 -> {
                        assertThat(request.headers[HttpHeaders.IfNoneMatch]).isNull()
                        assetPageResponse(savedBody, "\"alice-saved\"", PAST_EXPIRY, 1)
                    }

                    2 -> {
                        assertThat(request.headers[HttpHeaders.IfNoneMatch]).isEqualTo("\"alice-saved\"")
                        assetPageResponse(duplicateItems, "\"alice-replacement\"", FUTURE_EXPIRY, 1)
                    }

                    else -> {
                        assertThat(request.headers[HttpHeaders.IfNoneMatch]).isEqualTo("\"alice-saved\"")
                        EsiMock.EsiResponse(
                            body = "",
                            status = HttpStatusCode.NotModified,
                            headers = mapOf(HttpHeaders.Expires to FUTURE_EXPIRY),
                        )
                    }
                }
            }
            willResolveAssetNames(
                listOf(1),
                mapOf(34L to "Tritanium", 35L to "Pyerite"),
                mapOf(60000001L to "Jita"),
            )
        }
        val initial = runBlocking { system.listAssets() }

        system.reopenAssetServices()
        val failedReplacement = runBlocking { system.listAssets() }
        val afterNotModified = runBlocking { system.listAssets() }

        val oldRow = AssetRow("Alice", "Jita", "", "Tritanium", 7, 34)
        assertThat(initial.items).containsExactly(oldRow)
        assertThat(failedReplacement.failures).hasSize(1)
        assertThat(failedReplacement.items).containsExactly(oldRow)
        assertThat(afterNotModified.failures).isEmpty()
        assertThat(afterNotModified.items).containsExactly(oldRow)
        system.esi.characterAssets(1).assertPagesRequested(1, 1, 1)
    }

    @Test
    fun `removing and re-adding one character leaves other assets intact and clears its old pages`() {
        val originalAlice = """[{"item_id":101,"type_id":34,"quantity":7,"location_id":60000001,"location_type":"station"}]"""
        val newAlice = """[{"item_id":103,"type_id":34,"quantity":2,"location_id":60000001,"location_type":"station"}]"""
        val bob = """[{"item_id":202,"type_id":35,"quantity":3,"location_id":60000002,"location_type":"station"}]"""
        system.givenCharacters(1L to "Alice", 2L to "Bob")
        var aliceRequests = 0
        system.esi.apply {
            characterAssets(1).willRespond { request ->
                aliceRequests++
                assertThat(request.headers[HttpHeaders.IfNoneMatch]).isNull()
                val body = if (aliceRequests == 1) originalAlice else newAlice
                val etag = if (aliceRequests == 1) "\"alice-before-removal\"" else "\"alice-after-readd\""
                assetPageResponse(body, etag, FUTURE_EXPIRY, 1)
            }
            characterAssets(2).willReturnAssetPage(bob, "\"bob-assets\"", FUTURE_EXPIRY)
            willResolveAssetNames(
                listOf(1, 2),
                mapOf(34L to "Tritanium", 35L to "Pyerite"),
                mapOf(60000001L to "Jita", 60000002L to "Amarr"),
            )
        }
        val initial = runBlocking { system.listAssets() }

        system.removeCharacter(1)
        system.sso.next = {
            EveCharacter(1, "Alice", AuthToken("new-access", "new-refresh", system.now.plusSeconds(3600)))
        }
        runBlocking { system.addCharacter() }
        system.reopenAssetServices()
        val remainingCache = system.cachedAssets()
        val afterReAdd = runBlocking { system.listAssets() }

        assertThat(initial.items.map(AssetRow::characterName)).containsExactlyInAnyOrder("Alice", "Bob")
        assertThat(remainingCache.items.map(AssetRow::characterName)).containsExactly("Bob")
        assertThat(afterReAdd.failures).isEmpty()
        assertThat(afterReAdd.items).containsExactlyInAnyOrder(
            AssetRow("Alice", "Jita", "", "Tritanium", 2, 34),
            AssetRow("Bob", "Amarr", "", "Pyerite", 3, 35),
        )
        system.esi.characterAssets(1).assertPagesRequested(1, 1)
        system.esi.characterAssets(2).assertPagesRequested(1)
    }

    private fun FeatureSystem.givenCharacters(vararg fixtures: Pair<Long, String>) {
        given {
            fixtures.forEach { (id, name) ->
                this.characters().id(id).name(name).status("VALID")
                    .token_expires_at(now.plusSeconds(3600))
            }
        }
    }

    private fun EsiMock.willResolveAssetNames(
        characterIds: List<Long>,
        typeNames: Map<Long, String>,
        locations: Map<Long, String>,
    ) {
        val types = typeNames.entries.joinToString(prefix = "[", postfix = "]") { (id, name) ->
            """{"id":$id,"name":"$name"}"""
        }
        universeNames().willReturn(types)
        characterIds.forEach { characterAssetNames(it).willReturn("[]") }
        locations.forEach { (id, name) -> universeStations(id).willReturn("""{"name":"$name"}""") }
    }

    private fun EsiMock.Endpoint.willReturnAssetPage(
        body: String,
        etag: String,
        expires: String,
        pageCount: Int = 1,
    ) {
        willReturn(
            body,
            headers = assetPageHeaders(etag, expires, pageCount),
        )
    }

    private fun assetPageResponse(body: String, etag: String, expires: String, pageCount: Int) =
        EsiMock.EsiResponse(body, headers = assetPageHeaders(etag, expires, pageCount))

    private fun assetPageHeaders(etag: String, expires: String, pageCount: Int) = mapOf(
        HttpHeaders.ETag to etag,
        HttpHeaders.Expires to expires,
        "X-Pages" to pageCount.toString(),
    )

    private companion object {
        const val FUTURE_EXPIRY = "Sun, 08 Nov 2026 13:00:00 GMT"
        const val PAST_EXPIRY = "Sun, 08 Nov 2026 11:00:00 GMT"
    }
}
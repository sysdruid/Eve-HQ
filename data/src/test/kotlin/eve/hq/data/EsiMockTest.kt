package eve.hq.data

import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EsiMockTest {
    @Test
    fun `predicate assertions find matching and ignore non-matching requests`() = runBlocking {
        val esi = EsiMock {
            characterAssets(123).willReturn("[]")
        }
        esi.client.get("${esi.baseUrl}characters/123/assets/?page=2")

        esi.assertAnyRequest {
            it.method == HttpMethod.Get && it.path == "/latest/characters/123/assets/" &&
                it.queryParameters["page"] == "2"
        }
        esi.assertNoRequest { it.path == "/latest/universe/names/" }
    }

    @Test
    fun `predicate assertions handle an empty request history`() {
        val esi = EsiMock {}

        esi.assertNoRequest { true }
        val failure = assertFailsWith<AssertionError> {
            esi.assertAnyRequest { true }
        }

        assertThat(failure.message.orEmpty())
            .contains("Expected an ESI request matching the predicate")
            .contains("captured ESI requests")
            .contains("[]")
    }

    @Test
    fun `endpoint assertions match method path and optional page`() = runBlocking {
        val esi = EsiMock {
            characterAssets(123).page(1).willReturn("[]")
            characterAssets(123).page(2).willReturn("[]")
        }
        esi.client.get("${esi.baseUrl}characters/123/assets/?page=1")
        esi.client.get("${esi.baseUrl}characters/123/assets/?page=2")

        esi.characterAssets(123).assertRequested()
        esi.characterAssets(123).page(1).assertRequested()
        esi.characterAssets(123).page(2).assertRequested()
        esi.characterAssets(123).page(3).assertNotRequested()
        esi.Endpoint(HttpMethod.Post, "/latest/characters/123/assets/").assertNotRequested()
    }

    @Test
    fun `endpoint page sequence preserves request order and duplicates`() = runBlocking {
        val esi = EsiMock {
            characterAssets(123).willReturn("[]")
            characterAssets(456).willReturn("[]")
            universeNames().willReturn("[]")
        }
        esi.client.get("${esi.baseUrl}characters/123/assets/?page=2")
        esi.client.get("${esi.baseUrl}characters/456/assets/?page=1")
        esi.client.get("${esi.baseUrl}characters/123/assets/?page=1")
        esi.client.get("${esi.baseUrl}characters/123/assets/?page=2")
        esi.client.post("${esi.baseUrl}universe/names/")

        esi.characterAssets(123).assertPagesRequested(2, 1, 2)
    }

    @Test
    fun `page sequence diagnostics retain missing and malformed requests without exposing values`() = runBlocking<Unit> {
        val esi = EsiMock {
            characterAssets(123).willReturn("[]")
        }
        esi.client.get("${esi.baseUrl}characters/123/assets/?page=1")
        esi.client.get("${esi.baseUrl}characters/123/assets/")
        esi.client.get("${esi.baseUrl}characters/123/assets/?page=secret-page")

        val failure = assertFailsWith<AssertionError> {
            esi.characterAssets(123).assertPagesRequested(1, 2)
        }

        assertThat(failure.message.orEmpty())
            .contains("Expected ESI endpoint GET /latest/characters/123/assets/ page sequence [1, 2]")
            .contains("captured page sequence: [1, <missing>, <invalid>]")
            .doesNotContain("secret-page")
    }

    @Test
    fun `assertion failures show useful request details without secrets`() = runBlocking {
        val esi = EsiMock {
            characterAssetNames(123).willReturn("[]")
        }
        esi.client.post("${esi.baseUrl}characters/123/assets/names/?token=secret-query&page=secret-page") {
            header(HttpHeaders.Authorization, "Bearer secret-header")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"secret-body"}""")
        }

        assertEquals("""{"name":"secret-body"}""", esi.requests.single().body)
        assertEquals("secret-page", esi.requests.single().queryParameters["page"])
        val failure = assertFailsWith<AssertionError> {
            esi.assertNoRequest { it.path.endsWith("/assets/names/") }
        }
        val message = failure.message.orEmpty()
        assertThat(message)
            .contains("POST")
            .contains("/latest/characters/123/assets/names/")
            .doesNotContain("secret-query", "secret-page", "secret-header", "secret-body")
        Unit
    }

    @Test
    fun `missing endpoint assertion describes the expected endpoint`() = runBlocking {
        val esi = EsiMock {
            characterAssets(123).willReturn("[]")
        }
        esi.client.get("${esi.baseUrl}characters/123/assets/?page=1")

        val failure = assertFailsWith<AssertionError> {
            esi.characterAssets(456).page(2).assertRequested()
        }

        assertThat(failure.message.orEmpty())
            .contains("GET")
            .contains("/latest/characters/456/assets/")
            .contains("page=2")
            .contains("/latest/characters/123/assets/")
        Unit
    }
}
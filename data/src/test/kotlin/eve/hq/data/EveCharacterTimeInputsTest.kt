package eve.hq.data

import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.LoginRequired
import eve.hq.data.esi.EveImplantSource
import eve.hq.data.esi.EveSkillSource
import eve.hq.data.esi.EveStructureTypes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class EveCharacterTimeInputsTest {
    private val alice = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))
    private val raitaru = 1_035_466_617_946L

    @Test
    fun `skills are the trained level by skill type id, and a skill never trained is absent`() = runBlocking<Unit> {
        val esi = EsiMock {
            characterSkills(123).willReturn(
                """{"skills":[{"skill_id":3380,"trained_skill_level":5,"active_skill_level":5,"skillpoints_in_skill":256000},
                    {"skill_id":11453,"trained_skill_level":4,"active_skill_level":4,"skillpoints_in_skill":181000}],
                    "total_sp":437000,"unallocated_sp":0}""",
            )
        }

        val levels = EveSkillSource(esi.client, esi.baseUrl).levels(alice)

        assertThat(levels).containsExactlyInAnyOrderEntriesOf(mapOf(3380L to 5, 11453L to 4))
        esi.assertAnyRequest { it.headers[HttpHeaders.Authorization] == "Bearer access" }
    }

    @Test
    fun `a character without skills has none, and a missing scope asks for a new login`() = runBlocking<Unit> {
        val none = EsiMock { characterSkills(123).willReturn("""{"skills":[],"total_sp":0}""") }
        val denied = EsiMock { characterSkills(123).willReturn("{}", HttpStatusCode.Forbidden) }

        assertThat(EveSkillSource(none.client, none.baseUrl).levels(alice)).isEmpty()
        assertFailsWith<LoginRequired> { EveSkillSource(denied.client, denied.baseUrl).levels(alice) }
    }

    @Test
    fun `implants are the type ids plugged in, none for an empty list, and a missing scope asks for a new login`() = runBlocking<Unit> {
        val plugged = EsiMock { characterImplants(123).willReturn("[27167,27170]") }
        val none = EsiMock { characterImplants(123).willReturn("[]") }
        val denied = EsiMock { characterImplants(123).willReturn("{}", HttpStatusCode.Forbidden) }

        assertThat(EveImplantSource(plugged.client, plugged.baseUrl).implants(alice)).containsExactlyInAnyOrder(27167L, 27170L)
        assertThat(EveImplantSource(none.client, none.baseUrl).implants(alice)).isEmpty()
        assertFailsWith<LoginRequired> { EveImplantSource(denied.client, denied.baseUrl).implants(alice) }
    }

    @Test
    fun `a structure's type comes from ESI`() = runBlocking<Unit> {
        val esi = EsiMock {
            universeStructures(raitaru).willReturn("""{"name":"Home","owner_id":1,"solar_system_id":30000142,"type_id":35825}""")
        }

        assertThat(EveStructureTypes(esi.client, esi.baseUrl).typeOf(alice, raitaru)).isEqualTo(35825)
        esi.assertAnyRequest { it.headers[HttpHeaders.Authorization] == "Bearer access" }
    }

    @Test
    fun `an NPC station has no structure type and is not asked about`() = runBlocking<Unit> {
        val esi = EsiMock { universeStructures(60003760).willReturn("""{"type_id":1}""") }

        assertThat(EveStructureTypes(esi.client, esi.baseUrl).typeOf(alice, 60003760)).isNull()
        esi.universeStructures(60003760).assertNotRequested()
    }

    @Test
    fun `a structure the character may not see has no known type, any other failure is reported`() = runBlocking<Unit> {
        val forbidden = EsiMock { universeStructures(raitaru).willReturn("{}", HttpStatusCode.Forbidden) }
        val missing = EsiMock { universeStructures(raitaru).willReturn("{}", HttpStatusCode.NotFound) }
        val broken = EsiMock { universeStructures(raitaru).willReturn("boom", HttpStatusCode.InternalServerError) }

        assertThat(EveStructureTypes(forbidden.client, forbidden.baseUrl).typeOf(alice, raitaru)).isNull()
        assertThat(EveStructureTypes(missing.client, missing.baseUrl).typeOf(alice, raitaru)).isNull()
        assertFailsWith<Exception> { EveStructureTypes(broken.client, broken.baseUrl).typeOf(alice, raitaru) }
    }
}

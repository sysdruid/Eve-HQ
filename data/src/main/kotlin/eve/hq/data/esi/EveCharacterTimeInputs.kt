package eve.hq.data.esi

import eve.hq.core.character.EveCharacter
import eve.hq.core.production.ImplantSource
import eve.hq.core.production.SkillSource
import eve.hq.core.production.StructureTypes
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

private fun root(baseUrl: String) = "${baseUrl.trimEnd('/')}/"

private suspend fun HttpClient.authorizedGet(url: String, character: EveCharacter): HttpResponse =
    get(url) { bearerAuth(character.token.accessToken) }

class EveSkillSource(private val http: HttpClient, baseUrl: String = "https://esi.evetech.net/latest/") : SkillSource {
    private val baseUrl = root(baseUrl)

    override suspend fun levels(character: EveCharacter): Map<Long, Int> {
        val body = http.authorizedGet("${baseUrl}characters/${character.id}/skills/", character).bodyOrFail("character skills", "skill")
        return json.decodeFromString<EsiSkills>(body).skills.associate { it.skillId to it.trainedSkillLevel }
    }

    @Serializable
    private data class EsiSkills(val skills: List<EsiSkill> = emptyList())

    @Serializable
    private data class EsiSkill(
        @SerialName("skill_id") val skillId: Long,
        @SerialName("trained_skill_level") val trainedSkillLevel: Int,
    )
}

class EveImplantSource(private val http: HttpClient, baseUrl: String = "https://esi.evetech.net/latest/") : ImplantSource {
    private val baseUrl = root(baseUrl)

    override suspend fun implants(character: EveCharacter): Set<Long> {
        val body = http.authorizedGet("${baseUrl}characters/${character.id}/implants/", character).bodyOrFail("character implants", "implant")
        return json.decodeFromString<List<Long>>(body).toSet()
    }
}

class EveStructureTypes(private val http: HttpClient, baseUrl: String = "https://esi.evetech.net/latest/") : StructureTypes {
    private val baseUrl = root(baseUrl)

    override suspend fun typeOf(character: EveCharacter, facilityId: Long): Long? {
        // NPC stations have ids below this and no engineering bonuses, so there is nothing to ask.
        if (facilityId < FIRST_STRUCTURE_ID) return null
        val response = http.authorizedGet("${baseUrl}universe/structures/$facilityId/", character)
        if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.NotFound) return null
        return json.decodeFromString<EsiStructure>(response.bodyOrFail("structure", "structure")).typeId
    }

    @Serializable
    private data class EsiStructure(@SerialName("type_id") val typeId: Long)

    private companion object {
        const val FIRST_STRUCTURE_ID = 1_000_000_000_000L
    }
}

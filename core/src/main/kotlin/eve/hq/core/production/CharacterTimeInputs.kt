package eve.hq.core.production

import eve.hq.core.character.EveCharacter

/** What ESI knows about a character and the SDE does not: the inputs of a job's run time. */
fun interface SkillSource {
    /** The level the character has trained in each skill, by skill type id; skills it has not trained are absent. */
    suspend fun levels(character: EveCharacter): Map<Long, Int>
}

fun interface ImplantSource {
    /** The type ids of the implants the character has plugged in. */
    suspend fun implants(character: EveCharacter): Set<Long>
}

fun interface StructureTypes {
    /** The type of the structure [facilityId], or null for an NPC station and for a structure the character cannot see. */
    suspend fun typeOf(character: EveCharacter, facilityId: Long): Long?
}

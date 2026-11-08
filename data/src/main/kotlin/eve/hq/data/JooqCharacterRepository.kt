package eve.hq.data

import eve.hq.core.character.AuthToken
import eve.hq.core.character.CharacterRepository
import eve.hq.core.character.CharacterStatus
import eve.hq.core.character.EveCharacter
import eve.hq.data.jooq.tables.Characters.CHARACTERS
import eve.hq.data.jooq.tables.records.CharactersRecord
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.SQLDialect
import org.jooq.conf.RenderQuotedNames
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import java.time.ZoneOffset
import javax.sql.DataSource

class JooqCharacterRepository(dataSource: DataSource) : CharacterRepository {
    // Unquoted names let each database fold case itself (H2 upper, Postgres lower), so the lowercase
    // generated names match without H2-specific quoting.
    private val dsl: DSLContext = DSL.using(
        dataSource,
        SQLDialect.H2,
        Settings().withRenderQuotedNames(RenderQuotedNames.NEVER),
    )

    override fun all(): List<EveCharacter> = dsl.selectFrom(CHARACTERS).fetch(::toCharacter)

    override fun find(id: Long): EveCharacter? =
        dsl.selectFrom(CHARACTERS).where(CHARACTERS.ID.eq(id)).fetchOne(::toCharacter)
    override fun save(character: EveCharacter) {
        val (status, invalidReason) = when (val state = character.status) {
            CharacterStatus.Valid -> "VALID" to null
            is CharacterStatus.Invalid -> "INVALID" to state.reason
        }
        val columns = mapOf<Field<*>, Any?>(
            CHARACTERS.NAME to character.name,
            CHARACTERS.ACCESS_TOKEN to character.token.accessToken,
            CHARACTERS.REFRESH_TOKEN to character.token.refreshToken,
            CHARACTERS.TOKEN_EXPIRES_AT to character.token.expiresAt.atOffset(ZoneOffset.UTC),
            CHARACTERS.STATUS to status,
            CHARACTERS.INVALID_REASON to invalidReason,
        )
        dsl.insertInto(CHARACTERS)
            .set(CHARACTERS.ID, character.id)
            .set(columns)
            .onConflict(CHARACTERS.ID)
            .doUpdate()
            .set(columns)
            .execute()
    }

    override fun remove(id: Long) {
        dsl.deleteFrom(CHARACTERS).where(CHARACTERS.ID.eq(id)).execute()
    }

    private fun toCharacter(record: CharactersRecord) = EveCharacter(
        id = record.id,
        name = record.name,
        token = AuthToken(record.accessToken, record.refreshToken, record.tokenExpiresAt.toInstant()),
        status = when (record.status) {
            "VALID" -> CharacterStatus.Valid
            else -> CharacterStatus.Invalid(record.invalidReason.orEmpty())
        },
    )
}

package eve.hq.features.support

import eve.hq.core.asset.AssetListing
import eve.hq.core.asset.ListAssets
import eve.hq.core.asset.RefreshingAssetSource
import eve.hq.core.character.AddCharacter
import eve.hq.core.character.AuthToken
import eve.hq.core.character.CharacterRepository
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.ListCharacters
import eve.hq.core.character.MarkCharacterInvalid
import eve.hq.core.character.RefreshCharacterToken
import eve.hq.core.character.RemoveCharacter
import eve.hq.core.character.SsoAuthenticator
import eve.hq.core.character.TokenRefresher
import eve.hq.core.blueprint.BlueprintListing
import eve.hq.core.blueprint.ListBlueprints
import eve.hq.core.blueprint.RefreshingBlueprintSource
import eve.hq.core.industry.JobListing
import eve.hq.core.industry.JobSource
import eve.hq.core.industry.ListJobs
import eve.hq.core.industry.RefreshingJobSource
import eve.hq.data.EsiMock
import eve.hq.data.JooqAssetCacheRepository
import eve.hq.data.JooqBlueprintPageCache
import eve.hq.data.JooqCharacterRepository
import eve.hq.data.JooqJobPageCache
import eve.hq.data.JooqLocationNameCache
import eve.hq.data.Migrations
import eve.hq.data.StaticTypeNameRepository
import eve.hq.data.TokenCipher
import eve.hq.data.StaticBlueprintRepository
import eve.hq.data.esi.EveAssetSource
import eve.hq.data.esi.EveBlueprintSource
import eve.hq.data.esi.EveJobSource
import org.h2.jdbcx.JdbcDataSource
import org.jdbscript.JDBEngine
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The system under test for features: real use cases from `core`, a real in-memory H2 with the real
 * migrations and repositories. SSO and ESI are mocked only at their external boundaries. Each instance gets its own database.
 */
class FeatureSystem {
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    }.also(Migrations::migrate)

    private val engine = JDBEngine.builder(ICharactersSchema::class.java).dataSource(dataSource).build()

    val sso = FakeSso()
    val refresher = FakeTokenRefresher()
    private val repository: CharacterRepository = JooqCharacterRepository(
        dataSource,
        TokenCipher(ByteArray(32).also(SecureRandom()::nextBytes)),
    )
    val esi = EsiMock { }
    val now: Instant = Instant.parse("2026-11-08T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private var assets = newAssetList()
    private var jobs = newJobList()

    fun given(block: ICharactersSchema.() -> Unit) = engine.reset(block)

    fun listCharacters(): List<EveCharacter> = ListCharacters(repository)()
    suspend fun listAssets(): AssetListing = assets(listCharacters())
    fun cachedAssets(): AssetListing = assets.cached(listCharacters())
    suspend fun listJobs(): JobListing = jobs(listCharacters())
    suspend fun listBlueprints(): BlueprintListing = newBlueprintList()(listCharacters())
    fun reopenJobServices() {
        jobs = newJobList()
    }
    fun reopenAssetServices() {
        assets = newAssetList()
    }
    suspend fun addCharacter(): EveCharacter = AddCharacter(sso, repository)()
    fun markInvalid(id: Long, reason: String) = MarkCharacterInvalid(repository)(id, reason)
    fun removeCharacter(id: Long) = RemoveCharacter(repository)(id)
    suspend fun refreshToken(id: Long) = RefreshCharacterToken(repository, refresher)(id)

    private fun newJobList() = ListJobs(newJobSource(), clock)

    private fun newJobSource(): JobSource = RefreshingJobSource(
        repository,
        RefreshCharacterToken(repository, refresher),
        clock,
        EveJobSource(
            http = esi.client,
            baseUrl = esi.baseUrl,
            staticTypeNames = StaticTypeNameRepository(dataSource),
            pageCache = JooqJobPageCache(dataSource),
            clock = clock,
            locationNames = JooqLocationNameCache(dataSource),
        ),
    )

    private fun newBlueprintList() = ListBlueprints(
        RefreshingBlueprintSource(
            repository,
            RefreshCharacterToken(repository, refresher),
            clock,
            EveBlueprintSource(
                http = esi.client,
                baseUrl = esi.baseUrl,
                staticTypeNames = StaticTypeNameRepository(dataSource),
                staticBlueprints = StaticBlueprintRepository(dataSource),
                locationNames = JooqLocationNameCache(dataSource),
                pageCache = JooqBlueprintPageCache(dataSource),
                clock = clock,
            ),
        ),
        newJobSource(),
    )

    private fun newAssetList(): ListAssets {
        val source = EveAssetSource(
            http = esi.client,
            baseUrl = esi.baseUrl,
            staticTypeNames = StaticTypeNameRepository(dataSource),
            assetCache = JooqAssetCacheRepository(dataSource),
            clock = clock,
            locationNames = JooqLocationNameCache(dataSource),
        )
        val refreshingSource = RefreshingAssetSource(
            repository,
            RefreshCharacterToken(repository, refresher),
            clock,
            source,
        )
        return ListAssets(refreshingSource)
    }
}

class FakeSso : SsoAuthenticator {
    var next: () -> EveCharacter = { error("no login scripted") }

    override suspend fun login(): EveCharacter = next()
}

class FakeTokenRefresher : TokenRefresher {
    var next: (String) -> AuthToken = { error("no refresh scripted") }

    override suspend fun refresh(refreshToken: String): AuthToken = next(refreshToken)
}

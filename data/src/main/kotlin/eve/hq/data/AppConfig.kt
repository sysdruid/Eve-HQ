package eve.hq.data

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class MissingConfig(message: String) : Exception(message)

data class AppConfig(
    val ssoClientId: String,
    val ssoScopes: List<String> = DEFAULT_SCOPES,
) {
    companion object {
        val REQUIRED_SCOPES = listOf("esi-assets.read_assets.v1", "esi-universe.read_structures.v1")

        // Tokens only carry the scopes granted at login, so request everything up front: adding a feature later
        // must not force every character through SSO again. Narrow via sso.scopes before distributing the app.
        val ALL_ESI_SCOPES = listOf(
            "esi-access.read_lists.v1",
            "esi-activities.read_character.v1",
            "esi-alliances.read_contacts.v1",
            "esi-assets.read_assets.v1",
            "esi-assets.read_corporation_assets.v1",
            "esi-calendar.read_calendar_events.v1",
            "esi-calendar.respond_calendar_events.v1",
            "esi-characters.read_agents_research.v1",
            "esi-characters.read_blueprints.v1",
            "esi-characters.read_contacts.v1",
            "esi-characters.read_corporation_roles.v1",
            "esi-characters.read_fatigue.v1",
            "esi-characters.read_freelance_jobs.v1",
            "esi-characters.read_fw_stats.v1",
            "esi-characters.read_loyalty.v1",
            "esi-characters.read_medals.v1",
            "esi-characters.read_notifications.v1",
            "esi-characters.read_standings.v1",
            "esi-characters.read_titles.v1",
            "esi-characters.write_contacts.v1",
            "esi-clones.read_clones.v1",
            "esi-clones.read_implants.v1",
            "esi-contracts.read_character_contracts.v1",
            "esi-contracts.read_corporation_contracts.v1",
            "esi-corporations.read_blueprints.v1",
            "esi-corporations.read_contacts.v1",
            "esi-corporations.read_container_logs.v1",
            "esi-corporations.read_corporation_membership.v1",
            "esi-corporations.read_divisions.v1",
            "esi-corporations.read_facilities.v1",
            "esi-corporations.read_freelance_jobs.v1",
            "esi-corporations.read_fw_stats.v1",
            "esi-corporations.read_medals.v1",
            "esi-corporations.read_projects.v1",
            "esi-corporations.read_standings.v1",
            "esi-corporations.read_starbases.v1",
            "esi-corporations.read_structures.v1",
            "esi-corporations.read_titles.v1",
            "esi-corporations.track_members.v1",
            "esi-fittings.read_fittings.v1",
            "esi-fittings.write_fittings.v1",
            "esi-fleets.read_fleet.v1",
            "esi-fleets.write_fleet.v1",
            "esi-industry.read_character_jobs.v1",
            "esi-industry.read_character_mining.v1",
            "esi-industry.read_corporation_jobs.v1",
            "esi-industry.read_corporation_mining.v1",
            "esi-killmails.read_corporation_killmails.v1",
            "esi-killmails.read_killmails.v1",
            "esi-location.read_location.v1",
            "esi-location.read_online.v1",
            "esi-location.read_ship_type.v1",
            "esi-mail.organize_mail.v1",
            "esi-mail.read_mail.v1",
            "esi-mail.send_mail.v1",
            "esi-markets.read_character_orders.v1",
            "esi-markets.read_corporation_orders.v1",
            "esi-markets.structure_markets.v1",
            "esi-planets.manage_planets.v1",
            "esi-planets.read_customs_offices.v1",
            "esi-search.search_structures.v1",
            "esi-skills.read_skillqueue.v1",
            "esi-skills.read_skills.v1",
            "esi-structures.read_character.v1",
            "esi-structures.read_corporation.v1",
            "esi-ui.open_window.v1",
            "esi-ui.write_waypoint.v1",
            "esi-universe.read_structures.v1",
            "esi-wallet.read_character_wallet.v1",
            "esi-wallet.read_corporation_wallets.v1",
            "esi.activity.char:read",
            "esi.cosmetic.char:read",
        )
        val DEFAULT_SCOPES = listOf("publicData") + ALL_ESI_SCOPES

        const val FILE = "config.properties"
        const val CLIENT_ID_ENV = "EVE_HQ_CLIENT_ID"
        private const val CLIENT_ID_KEY = "sso.clientId"
        private const val SCOPES_KEY = "sso.scopes"

        fun load(dataDir: Path, env: Map<String, String> = System.getenv()): AppConfig {
            val file = dataDir.resolve(FILE)
            val clientId = env[CLIENT_ID_ENV]?.trim()?.takeIf(String::isNotEmpty)
                ?: readProperty(file, CLIENT_ID_KEY)
                ?: throw MissingConfig("No EVE client id. Set $CLIENT_ID_KEY=<your client id> in $file or the $CLIENT_ID_ENV environment variable.")
            val scopes = readProperty(file, SCOPES_KEY).orEmpty().split(Regex("[\\s,]+")).filter(String::isNotEmpty)
            return AppConfig(
                ssoClientId = clientId,
                ssoScopes = (scopes.ifEmpty { DEFAULT_SCOPES } + REQUIRED_SCOPES).distinct(),
            )
        }

        private fun readProperty(file: Path, key: String): String? {
            if (!Files.isRegularFile(file)) return null
            val properties = Properties().apply { Files.newBufferedReader(file).use(::load) }
            return properties.getProperty(key)?.trim()?.takeIf(String::isNotEmpty)
        }
    }
}

package eve.hq.data.sso

import eve.hq.data.AppConfig

data class SsoConfig(
    val clientId: String,
    val callbackPort: Int = 8765,
    val scopes: List<String> = AppConfig.DEFAULT_SCOPES,
    val authorizeUrl: String = "https://login.eveonline.com/v2/oauth/authorize",
    val tokenUrl: String = "https://login.eveonline.com/v2/oauth/token",
) {
    val redirectUri: String get() = "http://localhost:$callbackPort/callback"
}

package eve.hq.data.sso

data class SsoConfig(
    val clientId: String,
    val callbackPort: Int = 8765,
    val scopes: List<String> = listOf("publicData"),
    val authorizeUrl: String = "https://login.eveonline.com/v2/oauth/authorize",
    val tokenUrl: String = "https://login.eveonline.com/v2/oauth/token",
) {
    val redirectUri: String get() = "http://localhost:$callbackPort/callback"
}

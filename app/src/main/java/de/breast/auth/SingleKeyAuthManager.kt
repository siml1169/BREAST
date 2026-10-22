package de.breast.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class SingleKeyAuthException(
    override val message: String,
    val httpCode: Int? = null
) : IllegalStateException(message)

class SingleKeyAuthManager(
    private val httpClient: OkHttpClient,
    private val tokenStore: TokenStore
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val AUTHORIZE_ENDPOINT = "https://singlekey-id.com/auth/connect/authorize"
        const val TOKEN_ENDPOINT = "https://singlekey-id.com/auth/connect/token"

        const val CLIENT_ID = "762162C0-FA2D-4540-AE66-6489F189FADC"
        const val REDIRECT_URI = "com.bosch.tt.dashtt.pointt://app/login"

        // Exact registered scopes for Bosch HomeCom Easy / PointT client (762162C0-FA2D-4540-AE66-6489F189FADC).
        // Note: `pointt.gateway.resource.dashapp` is required; passing `pointt.gateway.resource` triggers
        // SingleKey ID's "Misconfigured Application" (invalid_scope) error screen.
        val DEFAULT_SCOPES = listOf(
            "openid",
            "email",
            "profile",
            "offline_access",
            "pointt.gateway.claiming",
            "pointt.gateway.removal",
            "pointt.gateway.list",
            "pointt.gateway.users",
            "pointt.gateway.resource.dashapp",
            "pointt.castt.flow.token-exchange",
            "bacon"
        ).joinToString(" ")

        fun generateCodeVerifier(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun computeS256CodeChallenge(codeVerifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        fun extractAuthorizationCode(input: String): String {
            val trimmed = input.trim()
            if (trimmed.contains("code=")) {
                val queryPart = trimmed.substringAfter("?", trimmed)
                for (pair in queryPart.split("&")) {
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2 && parts[0] == "code") {
                        return URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
                    }
                }
            }
            return URLDecoder.decode(trimmed, StandardCharsets.UTF_8.name())
        }

        private fun encodeQueryComponent(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    data class AuthorizationRequestInfo(
        val authorizationUrl: String,
        val codeVerifier: String,
        val codeChallenge: String
    )

    suspend fun createAuthorizationRequest(
        customVerifier: String? = null
    ): AuthorizationRequestInfo {
        val verifier = customVerifier ?: generateCodeVerifier()
        val challenge = computeS256CodeChallenge(verifier)
        tokenStore.savePendingPkceVerifier(verifier)

        val params = linkedMapOf(
            "redirect_uri" to REDIRECT_URI,
            "client_id" to CLIENT_ID,
            "response_type" to "code",
            "prompt" to "login",
            "scope" to DEFAULT_SCOPES,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
            "style_id" to "tt_bsch"
        )

        val queryString = params.entries.joinToString("&") { (k, v) ->
            "${encodeQueryComponent(k)}=${encodeQueryComponent(v)}"
        }
        val url = "$AUTHORIZE_ENDPOINT?$queryString"

        return AuthorizationRequestInfo(
            authorizationUrl = url,
            codeVerifier = verifier,
            codeChallenge = challenge
        )
    }

    suspend fun exchangeCodeForTokens(
        codeOrRedirectUrl: String,
        explicitCodeVerifier: String? = null
    ): OAuthTokens = withContext(Dispatchers.IO) {
        val code = extractAuthorizationCode(codeOrRedirectUrl)
        val verifier = explicitCodeVerifier
            ?: tokenStore.loadPendingPkceVerifier()
            ?: error("No PKCE code_verifier found. Generate an auth URL first.")

        val formBody = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("redirect_uri", REDIRECT_URI)
            .add("code", code)
            .add("code_verifier", verifier)
            .build()

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .post(formBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "SingleKey token exchange failed (HTTP ${response.code}): $bodyStr"
                )
            }
            val tokens = parseTokenResponse(bodyStr, fallbackRefreshToken = null)
            tokenStore.saveTokens(tokens)
            tokens
        }
    }

    suspend fun refreshAccessToken(explicitRefreshToken: String? = null): OAuthTokens =
        withContext(Dispatchers.IO) {
            val currentTokens = tokenStore.loadTokens()
            val refreshToken = explicitRefreshToken
                ?: currentTokens?.refreshToken
                ?: throw SingleKeyAuthException("No SingleKey ID refresh_token available. Please log in.")

            val formBody = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", CLIENT_ID)
                .add("refresh_token", refreshToken)
                .build()

            val request = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .post(formBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 400 || response.code == 401 || response.code == 403) {
                        throw SingleKeyAuthException(
                            "SingleKey ID session expired or revoked (HTTP ${response.code}). Please tap to log in again.",
                            httpCode = response.code
                        )
                    }
                    throw IllegalStateException(
                        "SingleKey token refresh failed (HTTP ${response.code}): $bodyStr"
                    )
                }
                val tokens = parseTokenResponse(bodyStr, fallbackRefreshToken = refreshToken)
                tokenStore.saveTokens(tokens)
                tokens
            }
        }

    suspend fun getValidAccessToken(): String = mutex.withLock {
        val stored = tokenStore.loadTokens()
            ?: throw SingleKeyAuthException("Not logged in to Bosch SingleKey ID. Tap to log in.")
        if (!stored.isExpired()) {
            return stored.accessToken
        }
        val refreshed = refreshAccessToken(stored.refreshToken)
        return refreshed.accessToken
    }

    private fun parseTokenResponse(bodyStr: String, fallbackRefreshToken: String?): OAuthTokens {
        val obj = json.parseToJsonElement(bodyStr).jsonObject
        val accessToken = obj["access_token"]?.jsonPrimitive?.content
            ?: error("Missing access_token in response: $bodyStr")
        val refreshToken = obj["refresh_token"]?.jsonPrimitive?.content
            ?: fallbackRefreshToken
            ?: error("Missing refresh_token in response: $bodyStr")
        val tokenType = obj["token_type"]?.jsonPrimitive?.content ?: "Bearer"
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        val idToken = obj["id_token"]?.jsonPrimitive?.content
        val scope = obj["scope"]?.jsonPrimitive?.content

        return OAuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = tokenType,
            expiresIn = expiresIn,
            idToken = idToken,
            scope = scope,
            issuedAtEpochSeconds = System.currentTimeMillis() / 1000
        )
    }
}

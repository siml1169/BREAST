package de.breast.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class OAuthTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("scope") val scope: String? = null,
    val issuedAtEpochSeconds: Long = System.currentTimeMillis() / 1000
) {
    fun isExpired(leewaySeconds: Long = 60): Boolean {
        val now = System.currentTimeMillis() / 1000
        return now >= (issuedAtEpochSeconds + expiresIn - leewaySeconds)
    }
}

interface TokenStore {
    suspend fun loadTokens(): OAuthTokens?
    suspend fun saveTokens(tokens: OAuthTokens)
    suspend fun clearTokens()

    suspend fun savePendingPkceVerifier(verifier: String)
    suspend fun loadPendingPkceVerifier(): String?
}

class FileTokenStore(
    private val tokenFile: File = File("tokens.json"),
    private val pkceFile: File = File(".pkce_verifier")
) : TokenStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    override suspend fun loadTokens(): OAuthTokens? {
        if (!tokenFile.exists()) return null
        return runCatching {
            json.decodeFromString<OAuthTokens>(tokenFile.readText())
        }.getOrNull()
    }

    override suspend fun saveTokens(tokens: OAuthTokens) {
        tokenFile.writeText(json.encodeToString(tokens))
    }

    override suspend fun clearTokens() {
        if (tokenFile.exists()) tokenFile.delete()
    }

    override suspend fun savePendingPkceVerifier(verifier: String) {
        pkceFile.writeText(verifier)
    }

    override suspend fun loadPendingPkceVerifier(): String? {
        if (!pkceFile.exists()) return null
        return pkceFile.readText().trim().takeIf { it.isNotEmpty() }
    }
}

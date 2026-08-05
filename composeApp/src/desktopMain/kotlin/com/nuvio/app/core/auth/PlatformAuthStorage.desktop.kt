package com.nuvio.app.core.auth

import com.nuvio.app.core.storage.DesktopStorage
import io.github.jan.supabase.auth.AuthConfig
import io.github.jan.supabase.auth.CodeVerifierCache
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val SESSION_KEY = "supabase_session"
private const val CODE_VERIFIER_KEY = "supabase_code_verifier"

internal actual fun AuthConfig.configurePlatformAuthStorage() {
    val store = DesktopStorage.store("nuvio_supabase_auth")
    sessionManager = DesktopSupabaseSessionManager(store)
    codeVerifierCache = DesktopSupabaseCodeVerifierCache(store)
}

private class DesktopSupabaseSessionManager(
    private val store: DesktopStorage.Store,
) : SessionManager {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun saveSession(session: UserSession) {
        store.putString(SESSION_KEY, json.encodeToString(session))
    }

    override suspend fun loadSession(): UserSession? =
        store.getString(SESSION_KEY)?.let { payload ->
            runCatching { json.decodeFromString<UserSession>(payload) }.getOrNull()
        }

    override suspend fun deleteSession() {
        store.remove(SESSION_KEY)
    }
}

private class DesktopSupabaseCodeVerifierCache(
    private val store: DesktopStorage.Store,
) : CodeVerifierCache {
    override suspend fun saveCodeVerifier(codeVerifier: String) {
        store.putString(CODE_VERIFIER_KEY, codeVerifier)
    }

    override suspend fun loadCodeVerifier(): String? =
        store.getString(CODE_VERIFIER_KEY)

    override suspend fun deleteCodeVerifier() {
        store.remove(CODE_VERIFIER_KEY)
    }
}
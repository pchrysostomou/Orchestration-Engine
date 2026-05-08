package com.workflowengine.server.auth

import com.workflowengine.runtime.db.ApiKeyStore
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.Principal
import io.ktor.server.auth.bearer

const val AUTH_SCHEME = "api-key"

fun Application.configureAuth(apiKeyStore: ApiKeyStore) {
    install(Authentication) {
        bearer(AUTH_SCHEME) {
            authenticate { credential ->
                if (apiKeyStore.validate(credential.token)) ApiKeyPrincipal(credential.token) else null
            }
        }
    }
}

data class ApiKeyPrincipal(val key: String) : Principal

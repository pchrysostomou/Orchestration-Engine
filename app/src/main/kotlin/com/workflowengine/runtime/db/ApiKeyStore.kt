package com.workflowengine.runtime.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID

data class ApiKeyRecord(val id: String, val name: String, val createdAt: LocalDateTime, val lastUsedAt: LocalDateTime?)

class ApiKeyStore {

    suspend fun create(name: String): Pair<String, ApiKeyRecord> =
        newSuspendedTransaction(Dispatchers.IO) {
            val id  = UUID.randomUUID().toString()
            val raw = "wfe_" + UUID.randomUUID().toString().replace("-", "")
            val now = LocalDateTime.now()
            ApiKeys.insert {
                it[ApiKeys.id]        = id
                it[ApiKeys.name]      = name
                it[ApiKeys.keyHash]   = sha256(raw)
                it[ApiKeys.createdAt] = now
            }
            Pair(raw, ApiKeyRecord(id, name, now, null))
        }

    suspend fun validate(rawKey: String): Boolean =
        newSuspendedTransaction(Dispatchers.IO) {
            val hash = sha256(rawKey)
            val row = ApiKeys.selectAll().where { ApiKeys.keyHash eq hash }.firstOrNull()
                ?: return@newSuspendedTransaction false
            ApiKeys.update({ ApiKeys.keyHash eq hash }) { it[ApiKeys.lastUsedAt] = LocalDateTime.now() }
            true
        }

    suspend fun list(): List<ApiKeyRecord> =
        newSuspendedTransaction(Dispatchers.IO) {
            ApiKeys.selectAll().map {
                ApiKeyRecord(
                    id         = it[ApiKeys.id],
                    name       = it[ApiKeys.name],
                    createdAt  = it[ApiKeys.createdAt],
                    lastUsedAt = it[ApiKeys.lastUsedAt]
                )
            }
        }

    suspend fun delete(id: String) =
        newSuspendedTransaction(Dispatchers.IO) {
            ApiKeys.deleteWhere { ApiKeys.id eq id }
        }

    suspend fun count(): Long =
        newSuspendedTransaction(Dispatchers.IO) {
            ApiKeys.selectAll().count()
        }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

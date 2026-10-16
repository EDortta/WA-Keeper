package br.com.wanotifkeeper

import kotlinx.coroutines.flow.Flow

/**
 * Contrato mínimo do domínio Entidades para listagem e criação.
 *
 * Não expõe o DAO ao consumidor. Identificadores e tipos persistidos
 * permanecem legados nesta fase para evitar migração destrutiva.
 */
interface EntityCatalog {
    fun entitiesFlow(): Flow<List<MemoryEntity>>
    suspend fun entityById(entityId: Long): MemoryEntity?
    suspend fun linksForEntity(entityId: Long): List<EntityLinkEntity>
    suspend fun createEntity(name: String, kind: String = "PERSON"): Long
}

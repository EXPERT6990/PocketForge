package com.theexpert9.pocketforge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<WorkspaceEntity>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun getById(id: String): WorkspaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workspace: WorkspaceEntity)

    @Query("UPDATE workspaces SET updatedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE workspaces SET activePromptProfileId = :profileId, updatedAt = :timestamp WHERE id = :id")
    suspend fun updatePromptProfile(id: String, profileId: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM chat_sessions WHERE workspaceId = :workspaceId ORDER BY updatedAt DESC")
    fun observeForWorkspace(workspaceId: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE workspaceId = :workspaceId ORDER BY updatedAt DESC")
    suspend fun getForWorkspaceOnce(workspaceId: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getById(sessionId: String): ChatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ChatSessionEntity)

    @Query("UPDATE chat_sessions SET providerId = :providerId, modelId = :modelId, updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun updateModel(sessionId: String, providerId: String, modelId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun updateTitle(sessionId: String, title: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE chat_sessions SET totalToolSteps = totalToolSteps + :delta, updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun incrementSteps(sessionId: String, delta: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE chat_sessions SET modelId = :newModelId WHERE providerId = :providerId AND modelId = :oldModelId")
    suspend fun migrateModelForProvider(providerId: String, oldModelId: String, newModelId: String)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeForSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getForSessionOnce(sessionId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Update
    suspend fun update(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)
}

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM file_snapshots WHERE workspaceId = :workspaceId ORDER BY timestamp DESC")
    fun observeForWorkspace(workspaceId: String): Flow<List<FileSnapshotEntity>>

    @Query("SELECT * FROM file_snapshots WHERE id = :id")
    suspend fun getById(id: String): FileSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: FileSnapshotEntity)

    @Query("UPDATE file_snapshots SET reverted = :reverted WHERE id = :id")
    suspend fun setReverted(id: String, reverted: Boolean)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM provider_configs ORDER BY isCustom ASC, displayName ASC")
    fun observeAll(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_configs")
    suspend fun getAllOnce(): List<ProviderConfigEntity>

    @Query("SELECT * FROM provider_configs WHERE id = :id")
    suspend fun getById(id: String): ProviderConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ProviderConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(providers: List<ProviderConfigEntity>)

    @Query("DELETE FROM provider_configs WHERE id = :id AND isCustom = 1")
    suspend fun deleteCustomById(id: String)
}

@Dao
interface CatalogModelDao {
    @Query("SELECT * FROM catalog_models ORDER BY providerName ASC, modelName ASC")
    fun observeAll(): Flow<List<CatalogModelEntity>>

    @Query("SELECT COUNT(*) FROM catalog_models")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: CatalogModelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(models: List<CatalogModelEntity>)

    @Query("DELETE FROM catalog_models WHERE compositeId = :compositeId")
    suspend fun deleteByCompositeId(compositeId: String)

    @Query("DELETE FROM catalog_models")
    suspend fun clearAll()
}

@Dao
interface PromptProfileDao {
    @Query("SELECT * FROM prompt_profiles ORDER BY isDefault DESC, name ASC")
    fun observeAll(): Flow<List<PromptProfileEntity>>

    @Query("SELECT * FROM prompt_profiles WHERE id = :id")
    suspend fun getById(id: String): PromptProfileEntity?

    @Query("SELECT * FROM prompt_profiles ORDER BY isDefault DESC LIMIT 1")
    suspend fun getDefault(): PromptProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: PromptProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<PromptProfileEntity>)

    @Query("DELETE FROM prompt_profiles WHERE id = :id AND isDefault = 0")
    suspend fun deleteNonDefault(id: String)
}

@Dao
interface TerminalDao {
    @Query("SELECT * FROM terminal_entries WHERE workspaceId = :workspaceId ORDER BY timestamp ASC")
    fun observeForWorkspace(workspaceId: String): Flow<List<TerminalEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TerminalEntryEntity)

    @Query("DELETE FROM terminal_entries WHERE workspaceId = :workspaceId")
    suspend fun clearWorkspace(workspaceId: String)
}

package br.com.wanotifkeeper

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notifications")
data class NotifEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val text: String,
    val timestamp: Long,
    val packageName: String = "com.whatsapp",
    /** Caminho no filesDir da imagem anexada à notificação, quando o WhatsApp a inclui. */
    val imagePath: String? = null,
    /** Caminho no filesDir do áudio de voz recebido, copiado da mídia do WhatsApp. */
    val audioPath: String? = null
)

/** Política de retenção de uma conversa. */
enum class RetentionMode { NEVER, CUSTOM, FOREVER }

@Entity(tableName = "conversation_settings")
data class ConversationSettings(
    @PrimaryKey val sender: String,
    val mode: String = RetentionMode.CUSTOM.name,
    val durationMillis: Long = RetentionPolicy.DEFAULT_WINDOW_MS
) {
    val retentionMode: RetentionMode
        get() = runCatching { RetentionMode.valueOf(mode) }.getOrDefault(RetentionMode.CUSTOM)
}

@Dao
interface NotifDao {
    @Insert
    suspend fun insert(notif: NotifEntity): Long

    @Query("UPDATE notifications SET audioPath = :path WHERE id = :id")
    suspend fun setAudioPath(id: Long, path: String)

    @Query("UPDATE notifications SET audioPath = NULL WHERE sender = :sender")
    suspend fun clearAudioForSender(sender: String)

    @Query("SELECT audioPath FROM notifications WHERE audioPath IS NOT NULL")
    suspend fun allAudioPaths(): List<String>

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC")
    fun allFlow(): Flow<List<NotifEntity>>

    @Query("SELECT * FROM notifications WHERE sender LIKE '%' || :q || '%' OR text LIKE '%' || :q || '%' ORDER BY timestamp DESC")
    fun searchFlow(q: String): Flow<List<NotifEntity>>

    @Query("SELECT * FROM notifications WHERE packageName = :pkg ORDER BY timestamp DESC")
    fun byPackageFlow(pkg: String): Flow<List<NotifEntity>>

    @Query("SELECT DISTINCT sender FROM notifications ORDER BY sender ASC")
    fun sendersFlow(): Flow<List<String>>

    /** Escopado por pacote: contatos com o mesmo nome de exibição no WhatsApp e no Business não se confundem. */
    @Query("SELECT DISTINCT sender FROM notifications WHERE packageName = :pkg ORDER BY sender ASC")
    suspend fun sendersByPackage(pkg: String): List<String>

    @Query("SELECT * FROM notifications WHERE sender = :sender ORDER BY timestamp DESC")
    fun bySenderFlow(sender: String): Flow<List<NotifEntity>>

    /** Para "leia as últimas mensagens de X" — mais recentes primeiro, já escopado por conta. */
    @Query("SELECT * FROM notifications WHERE sender = :sender AND packageName = :pkg ORDER BY timestamp DESC LIMIT :limit")
    suspend fun lastNForSender(sender: String, pkg: String, limit: Int): List<NotifEntity>

    @Query("SELECT * FROM notifications WHERE id = :id")
    suspend fun byId(id: Long): NotifEntity?

    @Query("SELECT * FROM notifications")
    suspend fun getAll(): List<NotifEntity>

    @Query("SELECT imagePath FROM notifications WHERE imagePath IS NOT NULL")
    suspend fun allImagePaths(): List<String>

    @Query("DELETE FROM notifications WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM notifications WHERE sender = :sender")
    suspend fun deleteSender(sender: String)

    @Query("DELETE FROM notifications WHERE sender = :sender AND timestamp < :cutoff")
    suspend fun purgeSender(sender: String, cutoff: Long)

    @Query("DELETE FROM notifications WHERE timestamp < :cutoff AND sender NOT IN (:excluded)")
    suspend fun purgeDefault(cutoff: Long, excluded: List<String>)

    @Query("SELECT COUNT(*) FROM notifications")
    suspend fun count(): Int
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM conversation_settings WHERE sender = :sender")
    suspend fun get(sender: String): ConversationSettings?

    @Query("SELECT * FROM conversation_settings")
    suspend fun getAll(): List<ConversationSettings>

    @Query("SELECT sender FROM conversation_settings")
    fun configuredSendersFlow(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: ConversationSettings)

    @Query("DELETE FROM conversation_settings WHERE sender = :sender")
    suspend fun delete(sender: String)
}

@Database(
    entities = [NotifEntity::class, ConversationSettings::class],
    version = 4,
    exportSchema = false
)
abstract class NotifDatabase : RoomDatabase() {
    abstract fun dao(): NotifDao
    abstract fun settings(): SettingsDao

    companion object {
        @Volatile private var INSTANCE: NotifDatabase? = null

        // Schema não mudou entre v1 e v2 — migration vazia preserva os dados
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { /* sem alteração de schema */ }
        }

        // v3: imagem anexada por notificação + retenção configurável por conversa
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN imagePath TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS conversation_settings (" +
                        "sender TEXT NOT NULL PRIMARY KEY, " +
                        "mode TEXT NOT NULL, " +
                        "durationMillis INTEGER NOT NULL)"
                )
            }
        }

        // v4: áudio de voz recebido copiado da mídia do WhatsApp
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN audioPath TEXT")
            }
        }

        fun get(ctx: Context): NotifDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext,
                NotifDatabase::class.java,
                "wanotif.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
        }
    }
}

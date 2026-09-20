package br.com.wanotifkeeper

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "notifications",
    indices = [Index(value = ["fingerprint"], unique = true)]
)
data class NotifEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val text: String,
    val timestamp: Long,
    val packageName: String = "com.whatsapp",
    val imagePath: String? = null,
    val audioPath: String? = null,
    val sourceType: String = "NOTIFICATION",
    val sourceRef: String? = null,
    val author: String? = null,
    val fingerprint: String? = null
)

@Entity(tableName = "memory_entities")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String = "PERSON",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "entity_links",
    indices = [
        Index(value = ["entityId"]),
        Index(value = ["packageName", "sender"], unique = true)
    ]
)
data class EntityLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityId: Long,
    val packageName: String,
    val sender: String,
    val role: String = "CONVERSATION",
    val createdAt: Long = System.currentTimeMillis()
)

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(notif: NotifEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM notifications WHERE fingerprint = :fingerprint)")
    suspend fun hasFingerprint(fingerprint: String): Boolean

    @Query("UPDATE notifications SET imagePath = :path WHERE id = :id")
    suspend fun setImagePath(id: Long, path: String)

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

    @Query("SELECT DISTINCT sender FROM notifications WHERE packageName = :pkg ORDER BY sender ASC")
    suspend fun sendersByPackage(pkg: String): List<String>

    @Query("SELECT * FROM notifications WHERE sender = :sender ORDER BY timestamp DESC")
    fun bySenderFlow(sender: String): Flow<List<NotifEntity>>

    @Query("SELECT * FROM notifications WHERE sender = :sender AND packageName = :pkg ORDER BY timestamp ASC")
    fun conversationFlow(sender: String, pkg: String): Flow<List<NotifEntity>>

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
interface MemoryDao {
    @Insert
    suspend fun insertEntity(entity: MemoryEntity): Long

    @Update
    suspend fun updateEntity(entity: MemoryEntity)

    @Query("SELECT * FROM memory_entities WHERE id = :id")
    suspend fun entityById(id: Long): MemoryEntity?

    @Query("SELECT * FROM memory_entities ORDER BY name COLLATE NOCASE")
    fun entitiesFlow(): Flow<List<MemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLink(link: EntityLinkEntity): Long

    @Query("SELECT * FROM entity_links WHERE entityId = :entityId ORDER BY createdAt")
    suspend fun linksForEntity(entityId: Long): List<EntityLinkEntity>

    @Query("SELECT * FROM entity_links WHERE packageName = :packageName AND sender = :sender LIMIT 1")
    suspend fun linkForConversation(packageName: String, sender: String): EntityLinkEntity?

    @Query("SELECT * FROM entity_links WHERE role = :role")
    suspend fun linksByRole(role: String): List<EntityLinkEntity>

    @Query(
        """SELECT n.* FROM notifications n
           INNER JOIN entity_links l
             ON l.packageName = n.packageName AND l.sender = n.sender
           WHERE l.entityId = :entityId
             AND (:query = '' OR n.text LIKE '%' || :query || '%' OR n.sender LIKE '%' || :query || '%')
           ORDER BY n.timestamp DESC
           LIMIT :limit"""
    )
    suspend fun contextForEntity(entityId: Long, query: String, limit: Int): List<NotifEntity>

    @Query("DELETE FROM entity_links WHERE entityId = :entityId AND packageName = :packageName AND sender = :sender")
    suspend fun unlink(entityId: Long, packageName: String, sender: String)
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
    entities = [
        NotifEntity::class,
        ConversationSettings::class,
        ScheduledMessageEntity::class,
        MemoryEntity::class,
        EntityLinkEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class NotifDatabase : RoomDatabase() {
    abstract fun dao(): NotifDao
    abstract fun memory(): MemoryDao
    abstract fun settings(): SettingsDao
    abstract fun scheduled(): ScheduledMessageDao

    companion object {
        @Volatile private var INSTANCE: NotifDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { }
        }

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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN audioPath TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS scheduled_messages (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "packageName TEXT NOT NULL, " +
                        "sender TEXT NOT NULL, " +
                        "text TEXT NOT NULL, " +
                        "state TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "claimedAt INTEGER, " +
                        "sentAt INTEGER, " +
                        "attempts INTEGER NOT NULL, " +
                        "lastError TEXT, " +
                        "nextAttemptAt INTEGER NOT NULL, " +
                        "triggerNotificationKey TEXT, " +
                        "triggeredAt INTEGER)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_scheduled_messages_conversation " +
                        "ON scheduled_messages (packageName, sender, state)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE scheduled_messages ADD COLUMN triggerType TEXT NOT NULL " +
                        "DEFAULT 'NEXT_INCOMING'"
                )
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN scheduledAt INTEGER")
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN mediaUri TEXT")
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN mediaMimeType TEXT")
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN mediaName TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_scheduled_messages_time " +
                        "ON scheduled_messages (triggerType, state, scheduledAt)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'NOTIFICATION'")
                db.execSQL("ALTER TABLE notifications ADD COLUMN sourceRef TEXT")
                db.execSQL("ALTER TABLE notifications ADD COLUMN author TEXT")
                db.execSQL("ALTER TABLE notifications ADD COLUMN fingerprint TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_notifications_fingerprint " +
                        "ON notifications (fingerprint)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memory_entities (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS entity_links (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "entityId INTEGER NOT NULL, " +
                        "packageName TEXT NOT NULL, " +
                        "sender TEXT NOT NULL, " +
                        "role TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entity_links_entityId ON entity_links (entityId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_entity_links_packageName_sender " +
                        "ON entity_links (packageName, sender)"
                )
            }
        }

        fun get(ctx: Context): NotifDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext,
                NotifDatabase::class.java,
                "wanotif.db"
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7
            ).build().also { INSTANCE = it }
        }
    }
}

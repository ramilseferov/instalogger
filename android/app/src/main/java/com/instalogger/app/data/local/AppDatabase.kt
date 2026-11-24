package com.instalogger.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TargetEntity::class,
        ChangeLogEntity::class,
        AvatarHistoryEntity::class,
        NotificationEntity::class,
        HighlightEntity::class,
        HighlightMediaEntity::class,
        PostEntity::class,
        FollowerAuditEntity::class,
        FollowerHistoryEntity::class,
        MediaAssetEntity::class,
        ProviderHealthEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun targetDao(): TargetDao
    abstract fun changeLogDao(): ChangeLogDao
    abstract fun avatarHistoryDao(): AvatarHistoryDao
    abstract fun notificationDao(): NotificationDao
    abstract fun highlightDao(): HighlightDao
    abstract fun postDao(): PostDao
    abstract fun followerAuditDao(): FollowerAuditDao
    abstract fun followerHistoryDao(): FollowerHistoryDao
    abstract fun mediaAssetDao(): MediaAssetDao
    abstract fun providerHealthDao(): ProviderHealthDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE targets ADD COLUMN avatarHash TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE targets ADD COLUMN profileHash TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE avatar_history ADD COLUMN fileHash TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE posts ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'image'")
                db.execSQL("ALTER TABLE posts ADD COLUMN slideCount INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE posts ADD COLUMN localThumbPath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE posts ADD COLUMN durationSeconds INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE posts ADD COLUMN downloadEngine TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE posts ADD COLUMN downloadStatus TEXT NOT NULL DEFAULT 'pending'")

                db.execSQL(
                    """
                    UPDATE posts
                    SET mediaType = CASE WHEN isVideo != 0 THEN 'video' ELSE 'image' END,
                        downloadStatus = CASE WHEN localPath IS NOT NULL AND localPath != '' THEN 'complete' ELSE 'pending' END
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_assets (
                        id TEXT NOT NULL PRIMARY KEY,
                        postId TEXT NOT NULL,
                        username TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        mediaType TEXT NOT NULL,
                        remoteUrl TEXT NOT NULL,
                        localVaultPath TEXT,
                        thumbVaultPath TEXT,
                        mimeType TEXT,
                        fileSize INTEGER NOT NULL,
                        sha256Hash TEXT,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        durationSeconds INTEGER,
                        providerName TEXT,
                        archivedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_assets_postId ON media_assets (postId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_assets_username_sha256Hash ON media_assets (username, sha256Hash)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS provider_health (
                        providerId TEXT NOT NULL PRIMARY KEY,
                        status TEXT NOT NULL,
                        consecutiveFailures INTEGER NOT NULL,
                        cooldownUntil INTEGER NOT NULL,
                        successCount INTEGER NOT NULL,
                        failureCount INTEGER NOT NULL,
                        lastError TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE targets ADD COLUMN latestStoryAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "instalogger_standalone.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}

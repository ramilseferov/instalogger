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
        FollowerHistoryEntity::class
    ],
    version = 6,
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

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "instalogger_standalone.db"
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}

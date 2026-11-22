package com.instalogger.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TargetEntity::class,
        ChangeLogEntity::class,
        AvatarHistoryEntity::class,
        NotificationEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun targetDao(): TargetDao
    abstract fun changeLogDao(): ChangeLogDao
    abstract fun avatarHistoryDao(): AvatarHistoryDao
    abstract fun notificationDao(): NotificationDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "instalogger_standalone.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}

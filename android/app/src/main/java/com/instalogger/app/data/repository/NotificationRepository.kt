package com.instalogger.app.data.repository

import com.instalogger.app.data.local.NotificationDao
import com.instalogger.app.data.local.NotificationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class NotificationRepository(private val notificationDao: NotificationDao) {

    val notifications: Flow<List<NotificationEntity>> = notificationDao.getAllNotifications()
    val unreadCount: Flow<Int> = notificationDao.getUnreadCount()

    suspend fun insertNotification(
        targetId: Long?,
        username: String,
        eventType: String,
        title: String,
        message: String
    ): Long = withContext(Dispatchers.IO) {
        notificationDao.insertNotification(
            NotificationEntity(
                targetId = targetId,
                username = username,
                eventType = eventType,
                title = title,
                message = message,
                timestamp = System.currentTimeMillis(),
                isRead = false
            )
        )
    }

    suspend fun markAsRead(id: Long) = withContext(Dispatchers.IO) {
        notificationDao.markAsRead(id)
    }

    suspend fun markAllAsRead() = withContext(Dispatchers.IO) {
        notificationDao.markAllAsRead()
    }

    suspend fun deleteNotification(id: Long) = withContext(Dispatchers.IO) {
        notificationDao.deleteById(id)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        notificationDao.clearAll()
    }
}

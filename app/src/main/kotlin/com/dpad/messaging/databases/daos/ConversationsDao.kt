package com.dpad.messaging.databases.daos

import androidx.room.*
import com.dpad.messaging.models.Conversation

@Dao
interface ConversationsDao {

    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY pinned DESC, date DESC")
    suspend fun getConversations(): List<Conversation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<Conversation>)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Query("UPDATE conversations SET read = 1, unread_count = 0 WHERE thread_id = :threadId")
    suspend fun markAsRead(threadId: Long)

    @Query("UPDATE conversations SET read = 0, unread_count = 1 WHERE thread_id = :threadId")
    suspend fun markAsUnread(threadId: Long)

    @Query("UPDATE conversations SET title = :title, uses_custom_title = 1 WHERE thread_id = :threadId")
    suspend fun setCustomTitle(threadId: Long, title: String)
}

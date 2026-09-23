package com.dpad.messaging.databases.daos

import androidx.room.*
import com.dpad.messaging.models.Attachment

@Dao
interface AttachmentsDao {

    @Query("SELECT * FROM attachments")
    suspend fun getAllAttachments(): List<Attachment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<Attachment>)

    @Query("DELETE FROM attachments")
    suspend fun deleteAllAttachments()
}

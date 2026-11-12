package com.zeroinstall.relay.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM outgoing_messages ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM outgoing_messages WHERE status != 'DELIVERED' ORDER BY createdAt ASC")
    suspend fun getUndelivered(): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM outgoing_messages WHERE status != 'DELIVERED'")
    suspend fun countUndelivered(): Int

    @Query("SELECT * FROM outgoing_messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: MessageEntity)

    @Query(
        """
        UPDATE outgoing_messages
        SET status = 'RELAYED_VIA_HELPER', relayAttempts = relayAttempts + 1,
            lastError = NULL, updatedAt = :now
        WHERE id IN (:ids) AND status != 'DELIVERED'
        """
    )
    suspend fun markRelayed(ids: List<String>, now: Long): Int

    @Query(
        """
        UPDATE outgoing_messages
        SET status = 'DELIVERED', deliveredVia = :via, deliveredAt = :now,
            cloudReceipt = :receipt, lastError = NULL, updatedAt = :now
        WHERE id = :id AND status != 'DELIVERED'
        """
    )
    suspend fun markDelivered(id: String, via: String, receipt: String?, now: Long): Int

    @Query(
        """
        UPDATE outgoing_messages
        SET status = 'PENDING', lastError = :error, updatedAt = :now
        WHERE id = :id AND status != 'DELIVERED'
        """
    )
    suspend fun markRelayFailed(id: String, error: String, now: Long): Int

    @Query("UPDATE outgoing_messages SET lastError = :error, updatedAt = :now WHERE id = :id AND status != 'DELIVERED'")
    suspend fun recordError(id: String, error: String, now: Long): Int

    @Query("DELETE FROM outgoing_messages WHERE status = 'DELIVERED'")
    suspend fun deleteDelivered(): Int
}

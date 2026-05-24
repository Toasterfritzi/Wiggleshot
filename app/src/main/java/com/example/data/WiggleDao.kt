package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WiggleDao {
    @Query("SELECT * FROM wiggle_captures ORDER BY timestamp DESC")
    fun getAllCaptures(): Flow<List<WiggleCapture>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCapture(capture: WiggleCapture): Long

    @Query("DELETE FROM wiggle_captures WHERE id = :id")
    suspend fun deleteCapture(id: Long)
}

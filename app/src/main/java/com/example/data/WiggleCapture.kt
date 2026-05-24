package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wiggle_captures")
data class WiggleCapture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imageAPath: String,
    val imageBPath: String,
    val blendedImagePath: String,
    val timestamp: Long = System.currentTimeMillis(),
    val alignmentOffsetX: Float = 0f,
    val alignmentOffsetY: Float = 0f,
    val speedFps: Float = 8f
)

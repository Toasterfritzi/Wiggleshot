package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wiggle_captures")
data class WiggleCapture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imageAPath: String,
    val imageBPath: String,
    val timestamp: Long = System.currentTimeMillis(),
    val prompt: String = "Hier sind zwei Bilder aus leicht unterschiedlichen Winkeln. Bitte erstelle ein genaues Zwischenbild, das den exakten Übergang zwischen beiden darstellt, um einen stereoskopischen 3D-Wiggle-Effekt zu erzeugen."
)

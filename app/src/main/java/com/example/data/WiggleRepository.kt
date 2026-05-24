package com.example.data

import kotlinx.coroutines.flow.Flow

class WiggleRepository(private val wiggleDao: WiggleDao) {
    val allCaptures: Flow<List<WiggleCapture>> = wiggleDao.getAllCaptures()

    suspend fun insertCapture(capture: WiggleCapture): Long {
        return wiggleDao.insertCapture(capture)
    }

    suspend fun deleteCapture(id: Long) {
        wiggleDao.deleteCapture(id)
    }
}

package com.example.syncaudio.models


data class SyncPacket(
    val type: String, // "sync", "chat", "ping"
    val timestamp: Long,
    val payload: String
)
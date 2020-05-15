package com.agora.videocall.utils

abstract class SinkConnector<T> {
    @Volatile
    var isConnected = false
        private set

    fun onConnected() {
        isConnected = true
    }

    @Synchronized
    fun onDisconnect() {
        isConnected = false
    }

    abstract fun onFormatChanged(format: Any?)
    abstract fun onFrameAvailable(frame: T)
}
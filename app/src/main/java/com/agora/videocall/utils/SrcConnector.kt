package com.agora.videocall.utils

import java.util.*

class SrcConnector<T> {
    private val plugList = LinkedList<SinkConnector<T>>()
    private var mFormat: Any? = null

    @get:Synchronized
    val isConnected: Boolean
        get() = !plugList.isEmpty()

    @Synchronized
    fun connect(sink: SinkConnector<T>) {
        if (!plugList.contains(sink)) {
            plugList.add(sink)
            sink.onConnected()
            if (mFormat != null) {
                sink.onFormatChanged(mFormat)
            }
        }
    }

    @Synchronized
    fun onFormatChanged(format: Any?) {
        mFormat = format
        val it: Iterator<SinkConnector<T>> = plugList.iterator()
        while (it.hasNext()) {
            val pin = it.next()
            pin.onFormatChanged(format)
        }
    }

    @Synchronized
    fun onFrameAvailable(frame: T) {
        val it: Iterator<SinkConnector<T>> = plugList.iterator()
        while (it.hasNext()) {
            val sink = it.next()
            sink.onFrameAvailable(frame)
        }
    }

    @Synchronized
    fun disconnect() {
        this.disconnect(null)
    }

    @Synchronized
    fun disconnect(sink: SinkConnector<T>?) {
        if (sink != null) {
            sink.onDisconnect()
            plugList.remove(sink)
        } else {
            val it: Iterator<*> = plugList.iterator()
            while (it.hasNext()) {
                val pin = it.next() as SinkConnector<*>
                pin.onDisconnect()
            }
            plugList.clear()
        }
    }
}
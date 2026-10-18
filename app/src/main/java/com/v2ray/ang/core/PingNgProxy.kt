package com.v2ray.ang.core

/**
 * Minimal JNI owner for the embedded PingNG SOCKS proxy.
 */
class PingNGProxy {
    companion object {
        init {
            System.loadLibrary("PingNG")
        }
    }

    @Volatile
    private var fd: Int = -1

    @Synchronized
    fun prepare(args: Array<String>) {
        check(fd < 0) { "PingNG proxy is already prepared" }
        val socket = jniCreateSocketWithCommandLine(args)
        check(socket >= 0) { "PingNG rejected the Desync command line" }
        fd = socket
    }

    fun runLoop(): Int {
        val socket = fd
        check(socket >= 0) { "PingNG proxy is not prepared" }
        return jniStartProxy(socket)
    }

    @Synchronized
    fun stop(): Int {
        val socket = fd
        if (socket < 0) return 0
        val result = jniStopProxy(socket)
        fd = -1
        return result
    }

    private external fun jniCreateSocketWithCommandLine(args: Array<String>): Int
    private external fun jniStartProxy(fd: Int): Int
    private external fun jniStopProxy(fd: Int): Int
}
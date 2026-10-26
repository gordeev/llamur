package org.motoai.runtime

import android.content.Context
import org.json.JSONObject

internal object NativeRuntime {
    init { System.loadLibrary("motoai-runtime") }

    interface Sink {
        fun onBytes(bytes: ByteArray)
        fun onPhase(phase: String) {}
        fun onProgress(bytes: ByteArray) {}
        fun onChat(bytes: ByteArray) {}
    }
    class Failure(val kind: String, message: String, val details: JSONObject = JSONObject()) : RuntimeException(message)

    private fun checked(bytes: ByteArray): JSONObject {
        val reply = JSONObject(String(bytes, Charsets.UTF_8))
        if (!reply.getBoolean("ok")) throw Failure(reply.getString("kind"), reply.getString("message"), reply)
        return reply
    }

    fun initialize(context: Context, backend: String = "cpu"): JSONObject = checked(initNative(context.applicationInfo.nativeLibraryDir.toByteArray(Charsets.UTF_8), backend.toByteArray(Charsets.UTF_8)))
    fun inspect(path:String):JSONObject = checked(inspectNative(path.toByteArray(Charsets.UTF_8)))
    fun ticket(): Long = ticketNative()
    fun cancel() = cancelNative()
    fun unload() = unloadNative()
    fun load(path: String, config: JSONObject, ticket: Long): JSONObject {
        val payload = JSONObject(config.toString()).put("path", path)
        return checked(loadNative(payload.toString().toByteArray(Charsets.UTF_8), ticket))
    }
    fun prepare(request: JSONObject): JSONObject = checked(prepareNative(request.toString().toByteArray(Charsets.UTF_8)))
    fun plan(path: String, config: JSONObject, ticket: Long): JSONObject = checked(planNative(
        JSONObject(config.toString()).put("path", path).toString().toByteArray(Charsets.UTF_8), ticket))
    fun generate(request: JSONObject, ticket: Long, sink: Sink): JSONObject =
        checked(generateNative(request.toString().toByteArray(Charsets.UTF_8), ticket, sink))

    private external fun initNative(directory: ByteArray, backend: ByteArray): ByteArray
    private external fun inspectNative(path:ByteArray):ByteArray
    private external fun ticketNative(): Long
    private external fun cancelNative()
    private external fun unloadNative()
    private external fun loadNative(config: ByteArray, ticket: Long): ByteArray
    private external fun prepareNative(request: ByteArray): ByteArray
    private external fun planNative(config: ByteArray, ticket: Long): ByteArray
    private external fun generateNative(request: ByteArray, ticket: Long, sink: Sink): ByteArray
}

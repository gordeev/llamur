package org.motoai.runtime

import org.json.JSONObject

/** Explicit public-fixture diagnostics, never run during ordinary load or generation. */
internal object BackendNumeric {
    init { System.loadLibrary("motoai-checks") }
    fun run(backend: String, gates: JSONObject): JSONObject = JSONObject(String(runNative(
        JSONObject().put("backend", backend).put("gates", gates).toString().toByteArray(Charsets.UTF_8)), Charsets.UTF_8))
    private external fun runNative(spec: ByteArray): ByteArray
}

package org.motoai.runtime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Optional HF credential storage only. AndroidKeyStore key is never backed up/exported. */
internal object LocalCredentials {
    private const val alias="motoai-hf-aes-v1"
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        return (store.getKey(alias,null) as? SecretKey)?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(context:Context):String {
        val encoded=context.getSharedPreferences("credentials",Context.MODE_PRIVATE).getString("hf",null)?:return ""
        return try {
            val parts=encoded.split(':');require(parts.size==2)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])))}
            String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),Charsets.UTF_8)
        }catch(_:Exception){forget(context);""}
    }
    fun save(context:Context,token:String) {
        require(token.isNotEmpty())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key())}
        val encrypted=cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(context.getSharedPreferences("credentials",Context.MODE_PRIVATE).edit().putString("hf",Base64.getEncoder().encodeToString(cipher.iv)+":"+Base64.getEncoder().encodeToString(encrypted)).commit())
    }
    fun forget(context:Context) {check(context.getSharedPreferences("credentials",Context.MODE_PRIVATE).edit().remove("hf").commit()) { UiText.get(R.string.ui_could_not_delete_saved_token) }}
}

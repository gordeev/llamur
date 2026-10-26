package org.motoai.runtime

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.InetAddress
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

/** Platform crypto only. Private key remains in AndroidKeyStore; public certificate is exportable. */
internal object ApiTls {
    private fun length(n: Int): ByteArray = if (n < 128) byteArrayOf(n.toByte()) else {
        val bytes = BigInteger.valueOf(n.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        byteArrayOf((128+bytes.size).toByte()) + bytes
    }
    private fun der(tag: Int, body: ByteArray) = byteArrayOf(tag.toByte()) + length(body.size) + body
    private fun seq(vararg items: ByteArray) = der(48, items.fold(byteArrayOf()) { a,b -> a+b })
    private fun oid(vararg n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); out.write(n[0]*40+n[1])
        for (item in n.drop(2)) { var value = item; val b = mutableListOf((value and 127).toByte()); value = value ushr 7
            while (value > 0) { b.add(0, ((value and 127) or 128).toByte()); value = value ushr 7 }; out.write(b.toByteArray()) }
        return der(6,out.toByteArray())
    }
    data class Identity(val context: SSLContext, val pem: String, val fingerprint: String)
    fun create(address: String): Identity {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; val alias = "motoai-local-api-ec-v1"
        if (!store.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                // Conscrypt hashes TLS transcripts before invoking the keystore key.
                // EC avoids the opaque RSA raw-padding path used by some OEM providers.
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=llamur local API")).setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(1577836800000L)).setCertificateNotAfter(Date(2208988800000L)).build())
            generator.generateKeyPair()
        }
        val key = store.getKey(alias,null) as PrivateKey; val publicKey = store.getCertificate(alias).publicKey
        val algorithm = seq(oid(1,2,840,10045,4,3,2))
        val name = X500Principal("CN=llamur local API").encoded
        val dateFormat = SimpleDateFormat("yyyyMMddHHmmss'Z'",Locale.US).apply { timeZone=TimeZone.getTimeZone("UTC") }
        fun date(d: Date)=der(24,dateFormat.format(d).toByteArray(Charsets.US_ASCII))
        val validity=seq(date(Date(System.currentTimeMillis()-86400000L)),date(Date(System.currentTimeMillis()+3650L*86400000L)))
        val sans = seq(der(0x82,"localhost".toByteArray()),der(0x87,InetAddress.getByName("127.0.0.1").address),der(0x87,InetAddress.getByName(address).address))
        fun extension(id: ByteArray, value: ByteArray, critical: Boolean = false)=if(critical) seq(id,der(1,byteArrayOf(-1)),der(4,value)) else seq(id,der(4,value))
        val extensions=der(0xa3,seq(extension(oid(2,5,29,19),seq(der(1,byteArrayOf(-1))),true),extension(oid(2,5,29,17),sans)))
        val serial=ByteArray(16).also { SecureRandom().nextBytes(it); it[0]=(it[0].toInt() and 127).toByte() }
        val tbs=seq(der(0xa0,der(2,byteArrayOf(2))),der(2,serial),algorithm,name,validity,name,publicKey.encoded,extensions)
        val signature=Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(tbs) }.sign()
        val encoded=seq(tbs,algorithm,der(3,byteArrayOf(0)+signature))
        val cert=CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate
        cert.verify(publicKey); cert.checkValidity()
        val manager=object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType:String?,issuers:Array<out java.security.Principal>?)=null
            override fun chooseClientAlias(keyType:Array<out String>?,issuers:Array<out java.security.Principal>?,socket:java.net.Socket?)=null
            override fun getServerAliases(keyType:String?,issuers:Array<out java.security.Principal>?)=if(keyType=="EC") arrayOf(alias) else null
            override fun chooseServerAlias(keyType:String?,issuers:Array<out java.security.Principal>?,socket:java.net.Socket?)=if(keyType=="EC") alias else null
            override fun getCertificateChain(a:String?)=if(a==alias) arrayOf(cert) else null
            override fun getPrivateKey(a:String?)=if(a==alias) key else null
            override fun chooseEngineServerAlias(keyType:String?,issuers:Array<out java.security.Principal>?,engine:SSLEngine?)=if(keyType=="EC")alias else null
        }
        val ssl=SSLContext.getInstance("TLS").apply { init(arrayOf(manager),null,SecureRandom()) }
        val pem="-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,byteArrayOf(10)).encodeToString(encoded)+"\n-----END CERTIFICATE-----\n"
        val fingerprint=MessageDigest.getInstance("SHA-256").digest(encoded).joinToString(":") { "%02X".format(it.toInt() and 255) }
        return Identity(ssl,pem,fingerprint)
    }
}

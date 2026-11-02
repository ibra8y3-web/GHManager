package com.ghmanager.app.data

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import org.json.JSONObject

object Crypto {
    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()) }

    /** libsodium sealed box, the format GitHub requires for Actions secrets. */
    fun seal(publicKeyB64: String, msg: ByteArray): String {
        val pk = Base64.decode(publicKeyB64, Base64.DEFAULT)
        val out = ByteArray(msg.size + Box.SEALBYTES)
        if (!sodium.cryptoBoxSeal(out, msg, msg.size.toLong(), pk)) throw IllegalStateException("Encryption failed")
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    suspend fun putSecret(base: String, name: String, value: ByteArray) {
        val pk = Github.get("$base/actions/secrets/public-key")
        val sealed = seal(pk.s("key"), value)
        Github.send("PUT", "$base/actions/secrets/${enc(name)}", JSONObject().put("encrypted_value", sealed).put("key_id", pk.s("key_id")))
    }
}

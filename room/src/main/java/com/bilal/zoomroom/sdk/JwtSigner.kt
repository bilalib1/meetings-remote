package com.bilal.zoomroom.sdk

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Dev-only: signs the Meeting SDK auth JWT on-device from a client ID/secret
 * pair the user enters once. Shipping builds must NOT embed the secret
 * (plan §11 Q2 — token-signing endpoint).
 */
object JwtSigner {

    fun sign(
        clientId: String,
        clientSecret: String,
        meetingNo: String = "",
        ttlSeconds: Long = 24 * 3600,
    ): String {
        val iat = System.currentTimeMillis() / 1000 - 30
        val exp = iat + ttlSeconds
        val header = b64("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        // Minimal {appKey,iat,exp,tokenExp} payload is rejected with the
        // misleading init error 3/-1; the legacy sdkKey/mn/role fields are
        // still required in practice (verified against SDK 7.0.5).
        val payload = b64(
            ("""{"appKey":"$clientId","sdkKey":"$clientId","mn":"$meetingNo","role":0,""" +
                """"iat":$iat,"exp":$exp,"tokenExp":$exp}""").toByteArray()
        )
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(clientSecret.toByteArray(), "HmacSHA256"))
        val sig = b64(mac.doFinal("$header.$payload".toByteArray()))
        return "$header.$payload.$sig"
    }

    private fun b64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

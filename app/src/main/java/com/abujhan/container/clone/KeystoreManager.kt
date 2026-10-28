package com.abujhan.container.clone

import android.content.Context
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * يحمّل مفتاح توقيع النسخ من الأصول (assets/clone_key.p12).
 *
 * كل النسخ تُوقَّع بنفس المفتاح، لذلك يمكن تحديث أي نسخة لاحقًا "في مكانها"
 * دون إلغاء تثبيت. غيّر المفتاح إن أردت هويّة توقيع مختلفة.
 */
object KeystoreManager {

    private const val ASSET = "clone_key.p12"
    private const val PASSWORD = "abujhan123"
    const val ALIAS = "clone"

    class KeyMaterial(val privateKey: PrivateKey, val certificate: X509Certificate)

    fun load(context: Context): KeyMaterial {
        val ks = KeyStore.getInstance("PKCS12")
        context.assets.open(ASSET).use { input ->
            ks.load(input, PASSWORD.toCharArray())
        }
        val alias = ks.aliases().asSequence().firstOrNull()
            ?: error("لا يوجد مفتاح في $ASSET")
        val key = ks.getKey(alias, PASSWORD.toCharArray()) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate
        return KeyMaterial(key, cert)
    }
}

package com.abujhan.container.clone

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File

/** يوقّع ملف APK (v1 + v2 + v3) بالمفتاح المدمج. */
object ApkSignerHelper {

    fun sign(context: Context, input: File, output: File, minSdk: Int) {
        val material = KeystoreManager.load(context)
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "ABUJHAN",
            material.privateKey,
            listOf(material.certificate),
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(minSdk)
            .build()
            .sign()
    }
}

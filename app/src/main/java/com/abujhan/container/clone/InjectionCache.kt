package com.abujhan.container.clone

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * يحفظ نتيجة فحص ملفات dex لكل APK أصلي: كم نداء موقع في كل ملف.
 *
 * المفتاح يشمل اسم الحزمة واسم الملف وحجمه وتاريخ تعديله، فإذا تغيّر إصدار التطبيق
 * يتغيّر الملف ويُعاد الفحص تلقائيًا. بهذا لا يُفحص كل ملف dex عند كل استنساخ،
 * والملفات التي لا تحتوي نداءات موقع تُنسخ مباشرة.
 */
object InjectionCache {

    private const val DIR = "injection_cache"

    /** يعيد عدد نداءات الموقع لكل ملف dex، أو null إن لم توجد نتيجة مطابقة. */
    fun load(context: Context, packageName: String, apk: File): Map<String, Int>? {
        val file = fileFor(context, packageName, apk)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            if (json.optString("key") != keyOf(apk)) return null
            val files = json.getJSONObject("files")
            files.keys().asSequence().associateWith { files.getInt(it) }
        }.getOrNull()
    }

    fun save(context: Context, packageName: String, apk: File, counts: Map<String, Int>) {
        val files = JSONObject()
        counts.forEach { (name, hooks) -> files.put(name, hooks) }
        val json = JSONObject()
            .put("key", keyOf(apk))
            .put("files", files)
        runCatching { fileFor(context, packageName, apk).writeText(json.toString()) }
    }

    /** الناتج المعاد كتابته لملف dex واحد، أو null إن لم يكن مخزّنًا لهذا الإصدار والمحوّل. */
    fun loadRewritten(context: Context, packageName: String, apk: File, dexName: String): ByteArray? {
        val file = rewrittenFile(context, packageName, apk, dexName)
        return if (file.exists()) file.readBytes() else null
    }

    /** يحفظ الناتج، ويحذف نسخ الإصدارات القديمة لنفس الحزمة والملف. */
    fun saveRewritten(context: Context, packageName: String, apk: File, dexName: String, bytes: ByteArray) {
        runCatching {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            dir.listFiles { f -> f.name.startsWith("$packageName.$dexName.") && f.name.endsWith(".rw") }
                ?.forEach { it.delete() }
            rewrittenFile(context, packageName, apk, dexName).writeBytes(bytes)
        }
    }

    private fun rewrittenFile(context: Context, packageName: String, apk: File, dexName: String): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val tag = sha(keyOf(apk) + "|" + SpoofRewriter.FINGERPRINT).take(16)
        return File(dir, "$packageName.$dexName.$tag.rw")
    }

    private fun sha(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun keyOf(apk: File): String = "${apk.name}|${apk.length()}|${apk.lastModified()}"

    private fun fileFor(context: Context, packageName: String, apk: File): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, "$packageName.${apk.name}.json")
    }
}

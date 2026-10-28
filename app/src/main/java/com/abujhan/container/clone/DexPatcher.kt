package com.abujhan.container.clone

import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * رقّع DEX بسيط: يعطّل نداءات `System.exit(int)` بتحويلها إلى `return-void`.
 *
 * يُستخدم لتجاوز فحوص "كشف التلاعب" في بعض التطبيقات 
 * تستدعي System.exit(0) عند فشل التحقّق من التوقيع.
 *
 * لا نستخدم محلّل DEX كاملًا؛ بل نجد فهرس الدالة System.exit(I)V من جدول
 * الدوال، ثم نبحث عن ترميز نداء invoke-static لها ونستبدله بـ return-void.
 */
object DexPatcher {

    /** @return عدد النداءات التي تمّ تعطيلها. */
    fun noOpSystemExit(dex: ByteArray): Int {
        if (dex.size < 112 || !isDex(dex)) return 0

        val systemTypeIndex = findTypeIndex(dex, "Ljava/lang/System;") ?: return 0
        val exitStringIndex = findStringIndex(dex, "exit") ?: return 0
        val methodIndex = findMethodIndex(dex, systemTypeIndex, exitStringIndex) ?: return 0

        var patched = 0
        var i = 0
        val limit = dex.size - 6
        while (i <= limit) {
            if (dex[i].toInt() and 0xFF == 0x71 && (dex[i + 1].toInt() and 0x0F) == 0x00) {
                val idx = (dex[i + 2].toInt() and 0xFF) or ((dex[i + 3].toInt() and 0xFF) shl 8)
                val unit2Low = dex[i + 4].toInt() and 0xFF
                val unit2High = dex[i + 5].toInt() and 0xFF
                if (idx == methodIndex && unit2High == 0x00 && unit2Low <= 0x0F && i % 2 == 0) {
                    // return-void + nop + nop  (نفس حجم invoke-static)
                    dex[i] = 0x0E
                    dex[i + 1] = 0x00
                    dex[i + 2] = 0x00
                    dex[i + 3] = 0x00
                    dex[i + 4] = 0x00
                    dex[i + 5] = 0x00
                    patched++
                }
            }
            i++
        }

        if (patched > 0) fixDexChecksums(dex)
        return patched
    }

    /** استبدال قيمة نصية بنفس الطول في الـ dex (بدون تغيير الفهارس). */
    fun replaceString(dex: ByteArray, oldValue: String, newValue: String): Boolean {
        val oldBytes = oldValue.toByteArray(Charsets.UTF_8)
        val newBytes = newValue.toByteArray(Charsets.UTF_8)
        if (oldBytes.size != newBytes.size) return false

        val index = findStringIndex(dex, oldValue) ?: return false
        val dataOff = u32(dex, stringIdsOff(dex) + index * 4)

        var p = dataOff
        while (p < dex.size && (dex[p].toInt() and 0x80) != 0) p++
        p++ // تجاوز طول utf16

        System.arraycopy(newBytes, 0, dex, p, newBytes.size)
        fixDexChecksums(dex)
        return true
    }

    // --------------------------------------------------------------- helpers

    /** تشخيص (للتطوير). */
    fun debug(dex: ByteArray): String {
        val sb = StringBuilder()
        sb.append("isDex=").append(isDex(dex)).append(" size=").append(dex.size).append('\n')
        val s = findStringIndex(dex, "Ljava/lang/System;")
        sb.append("systemStringIdx=").append(s).append('\n')
        val t = findTypeIndex(dex, "Ljava/lang/System;")
        sb.append("systemTypeIdx=").append(t).append('\n')
        val ex = findStringIndex(dex, "exit")
        sb.append("exitStringIdx=").append(ex).append('\n')
        if (t != null && ex != null) {
            val m = findMethodIndex(dex, t, ex)
            sb.append("exitMethodIdx=").append(m).append('\n')
            if (m != null) {
                var raw = 0
                var withArgs = 0
                var i = 0
                while (i < dex.size - 6) {
                    if ((dex[i].toInt() and 0xFF) == 0x71) {
                        val idx = (dex[i + 2].toInt() and 0xFF) or ((dex[i + 3].toInt() and 0xFF) shl 8)
                        if (idx == m) {
                            raw++
                            val lo = dex[i + 4].toInt() and 0xFF
                            val hi = dex[i + 5].toInt() and 0xFF
                            if (hi == 0 && lo <= 0x0F) withArgs++
                        }
                    }
                    i++
                }
                sb.append("rawMatches=").append(raw).append(" argMatches=").append(withArgs).append('\n')
                var exact = 0
                var evenOdd = ""
                var i2 = 0
                while (i2 < dex.size - 6) {
                    if ((dex[i2].toInt() and 0xFF) == 0x71) {
                        val idx = (dex[i2 + 2].toInt() and 0xFF) or ((dex[i2 + 3].toInt() and 0xFF) shl 8)
                        if (idx == m) {
                            val b1 = dex[i2 + 1].toInt() and 0x0F
                            val lo = dex[i2 + 4].toInt() and 0xFF
                            val hi = dex[i2 + 5].toInt() and 0xFF
                            if (b1 == 0 && hi == 0 && lo <= 0x0F) {
                                evenOdd += "off=$i2 parity=${i2 % 2} "
                                if (i2 % 2 == 0) exact++
                            }
                        }
                    }
                    i2++
                }
                sb.append("exact=").append(exact).append(" detail=").append(evenOdd).append('\n')
            }
        }
        return sb.toString()
    }

    private fun isDex(d: ByteArray): Boolean =
        d[0] == 'd'.code.toByte() && d[1] == 'e'.code.toByte() &&
            d[2] == 'x'.code.toByte() && d[3] == '\n'.code.toByte()

    private fun u32(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or
            ((d[off + 1].toInt() and 0xFF) shl 8) or
            ((d[off + 2].toInt() and 0xFF) shl 16) or
            ((d[off + 3].toInt() and 0xFF) shl 24)

    private fun u16(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)

    private fun stringIdsSize(d: ByteArray) = u32(d, 56)
    private fun stringIdsOff(d: ByteArray) = u32(d, 60)
    private fun typeIdsSize(d: ByteArray) = u32(d, 64)
    private fun typeIdsOff(d: ByteArray) = u32(d, 68)
    private fun methodIdsSize(d: ByteArray) = u32(d, 88)
    private fun methodIdsOff(d: ByteArray) = u32(d, 92)

    private fun readString(d: ByteArray, index: Int): String? {
        if (index < 0 || index >= stringIdsSize(d)) return null
        val dataOff = u32(d, stringIdsOff(d) + index * 4)
        var p = dataOff
        // uleb128 (utf16 length) — نتجاهله
        while (p < d.size && (d[p].toInt() and 0x80) != 0) p++
        p++
        // MUTF-8 حتى النهاية (0x00)
        val out = StringBuilder()
        while (p < d.size) {
            val b = d[p].toInt() and 0xFF
            if (b == 0) break
            out.append(b.toChar())
            p++
        }
        return out.toString()
    }

    private fun findStringIndex(d: ByteArray, value: String): Int? {
        for (i in 0 until stringIdsSize(d)) {
            if (readString(d, i) == value) return i
        }
        return null
    }

    private fun findTypeIndex(d: ByteArray, descriptor: String): Int? {
        val stringIndex = findStringIndex(d, descriptor) ?: return null
        for (i in 0 until typeIdsSize(d)) {
            if (u32(d, typeIdsOff(d) + i * 4) == stringIndex) return i
        }
        return null
    }

    private fun findMethodIndex(d: ByteArray, classTypeIndex: Int, nameStringIndex: Int): Int? {
        val base = methodIdsOff(d)
        for (i in 0 until methodIdsSize(d)) {
            val off = base + i * 8
            val classIdx = u16(d, off)
            val nameIdx = u32(d, off + 4)
            if (classIdx == classTypeIndex && nameIdx == nameStringIndex) return i
        }
        return null
    }

    /** يعيد حساب checksum (Adler-32) و signature (SHA-1) ليبقى الملف صالحًا. */
    private fun fixDexChecksums(d: ByteArray) {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(d, 32, d.size - 32)
        val signature = sha1.digest()
        System.arraycopy(signature, 0, d, 12, 20)

        val adler = Adler32()
        adler.update(d, 12, d.size - 12)
        val value = adler.value.toInt()
        d[8] = (value and 0xFF).toByte()
        d[9] = ((value ushr 8) and 0xFF).toByte()
        d[10] = ((value ushr 16) and 0xFF).toByte()
        d[11] = ((value ushr 24) and 0xFF).toByte()
    }
}

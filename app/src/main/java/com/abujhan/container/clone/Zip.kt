package com.abujhan.container.clone

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * قارئ/كاتب ZIP بسيط ومتحكَّم به، لأنّ بناء نسخة صالحة للتثبيت يتطلّب:
 *  - أن يبقى resources.arsc مخزَّنًا (STORED) ومحاذيًا إلى 4 بايتات
 *    (متطلَّب في أندرويد 11+).
 *
 * نستخدم حقلًا إضافيًا (extra field) معرّفه 0xD935 (نفس ما يفعله zipalign)
 * لضبط المحاذاة.
 */
object Zip {

    const val STORED = 0
    const val DEFLATED = 8

    class Entry(val name: String, val method: Int, val data: ByteArray)

    fun read(file: File): List<Entry> {
        val out = ArrayList<Entry>()
        ZipFile(file).use { zf ->
            val en = zf.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.isDirectory) continue
                val bytes = zf.getInputStream(e).use { it.readBytes() }
                val method = if (e.method == ZipEntry.STORED) STORED else DEFLATED
                out.add(Entry(e.name, method, bytes))
            }
        }
        return out
    }

    fun write(file: File, entries: List<Entry>) {
        val out = CountingOutputStream(FileOutputStream(file))
        val central = ArrayList<CentralRecord>()

        for (e in entries) {
            val nameBytes = e.name.toByteArray(Charsets.UTF_8)
            val crc = crc32(e.data)
            val stored = e.method == STORED
            val payload = if (stored) e.data else deflate(e.data)

            var extra = ByteArray(0)
            if (stored && e.name == "resources.arsc") {
                val base = out.count + 30 + nameBytes.size
                val need = (4 - (base % 4)) % 4
                if (need > 0) {
                    extra = ByteArray(4 + need)
                    extra[0] = 0x35
                    extra[1] = 0xD9.toByte()
                    extra[2] = (need and 0xFF).toByte()
                    extra[3] = ((need ushr 8) and 0xFF).toByte()
                }
            }

            val localOffset = out.count
            val lh = ByteArrayOutputStream()
            putU32(lh, 0x04034b50)
            putU16(lh, 20)
            putU16(lh, 0)
            putU16(lh, if (stored) STORED else DEFLATED)
            putU16(lh, 0)      // mod time
            putU16(lh, 0x0021) // mod date (1980-01-01)
            putU32(lh, crc)
            putU32(lh, payload.size)
            putU32(lh, e.data.size)
            putU16(lh, nameBytes.size)
            putU16(lh, extra.size)
            out.write(lh.toByteArray())
            out.write(nameBytes)
            out.write(extra)
            out.write(payload)

            central.add(
                CentralRecord(nameBytes, extra, crc, payload.size, e.data.size, stored, localOffset)
            )
        }

        val cdStart = out.count
        for (c in central) {
            val rec = ByteArrayOutputStream()
            putU32(rec, 0x02014b50)
            putU16(rec, 20) // version made by
            putU16(rec, 20) // version needed
            putU16(rec, 0)  // flags
            putU16(rec, if (c.stored) STORED else DEFLATED)
            putU16(rec, 0)
            putU16(rec, 0x0021)
            putU32(rec, c.crc)
            putU32(rec, c.compSize)
            putU32(rec, c.uncompSize)
            putU16(rec, c.nameBytes.size)
            putU16(rec, c.extra.size)
            putU16(rec, 0) // comment length
            putU16(rec, 0) // disk number start
            putU16(rec, 0) // internal attrs
            putU32(rec, 0) // external attrs
            putU32(rec, c.localOffset)
            out.write(rec.toByteArray())
            out.write(c.nameBytes)
            out.write(c.extra)
        }
        val cdSize = out.count - cdStart

        val eocd = ByteArrayOutputStream()
        putU32(eocd, 0x06054b50)
        putU16(eocd, 0)
        putU16(eocd, 0)
        putU16(eocd, central.size)
        putU16(eocd, central.size)
        putU32(eocd, cdSize)
        putU32(eocd, cdStart)
        putU16(eocd, 0)
        out.write(eocd.toByteArray())

        out.flush()
        out.close()
    }

    private fun crc32(data: ByteArray): Int {
        val c = CRC32()
        c.update(data)
        return c.value.toInt()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private class CentralRecord(
        val nameBytes: ByteArray,
        val extra: ByteArray,
        val crc: Int,
        val compSize: Int,
        val uncompSize: Int,
        val stored: Boolean,
        val localOffset: Int,
    )

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    private fun putU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun putU32(out: ByteArrayOutputStream, v: Int) {
        putU16(out, v and 0xFFFF)
        putU16(out, (v ushr 16) and 0xFFFF)
    }
}

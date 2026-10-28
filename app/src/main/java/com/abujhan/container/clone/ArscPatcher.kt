package com.abujhan.container.clone

/**
 * رقّع `resources.arsc`: يغيّر اسم الحزمة داخل جدول الموارد.
 *
 * هذا ضروري لأنّ `Resources.getIdentifier(name, type, getPackageName())` يقارن
 * اسم الحزمة المطلوب باسم الحزمة المخزَّن في resources.arsc. فإذا لم نغيّره
 * يفشل البحث ويعيد 0 (ما يسبّب انهيارات مثل Resource ID #0x0).
 *
 * اسم الحزمة مخزَّن في حقل ثابت الطول (128 محرف UTF-16) داخل ResTable_package،
 * لذا الاستبدال لا يغيّر أي أحجام أو إزاحات.
 */
object ArscPatcher {

    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val PACKAGE_NAME_CHARS = 128

    fun renamePackage(arsc: ByteArray, newPackage: String): Boolean {
        if (arsc.size < 12) return false
        if (u16(arsc, 0) != RES_TABLE_TYPE) return false

        val headerSize = u16(arsc, 2)
        var offset = headerSize
        while (offset + 8 <= arsc.size) {
            val type = u16(arsc, offset)
            val size = u32(arsc, offset + 4)
            if (type == RES_TABLE_PACKAGE_TYPE) {
                val nameOffset = offset + 12 // type(2)+headerSize(2)+size(4)+id(4)
                val nameEnd = nameOffset + PACKAGE_NAME_CHARS * 2
                if (nameEnd > arsc.size) return false

                for (i in 0 until PACKAGE_NAME_CHARS * 2) arsc[nameOffset + i] = 0
                val bytes = newPackage.toByteArray(Charsets.UTF_16LE)
                val copyLen = minOf(bytes.size, (PACKAGE_NAME_CHARS - 1) * 2)
                System.arraycopy(bytes, 0, arsc, nameOffset, copyLen)
                return true
            }
            if (size <= 0) break
            offset += size
        }
        return false
    }

    private fun u16(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or
            ((d[off + 1].toInt() and 0xFF) shl 8) or
            ((d[off + 2].toInt() and 0xFF) shl 16) or
            ((d[off + 3].toInt() and 0xFF) shl 24)
}

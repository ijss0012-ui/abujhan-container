package com.abujhan.container.clone.axml

import java.io.ByteArrayOutputStream

/**
 * محرّر مبسّط لملف AndroidManifest.xml الثنائي (AXML).
 *
 * لا يعتمد على أي مكتبة أندرويد خارجية، لذا يمكن اختباره على الـ JVM مباشرة.
 *
 * الوظائف المطلوبة للاستنساخ:
 *  - تغيير اسم الحزمة (package).
 *  - إعادة تسمية المكوّنات النسبية (‎.MainActivity‎) والصلاحيات المخصّصة ومزوّدي المحتوى (authorities).
 *  - حذف sharedUserId.
 *  - إضافة عنصر <provider> جديد داخل <application> لحقن كود الموقع الوهمي.
 *
 * الفكرة الأساسية: لا نعيد ترتيب جدول النصوص (String Pool) أبدًا، بل نضيف نصوصًا
 * جديدة في النهاية. هذا يحافظ على صحّة كل الفهارس (بما فيها ResourceMap) دون تعديلها.
 */
class AxmlEditor(private val original: ByteArray) {

    private val strings = ArrayList<String>()
    private val chunks = ArrayList<Any>() // ByteArray (خام) أو Element
    private var androidNs = -1

    init {
        parse()
        androidNs = indexOfString(ANDROID_NS)
    }

    // ------------------------------------------------------------------ parse

    private fun parse() {
        require(original.size >= 8) { "AXML too small" }
        require(u16(0) == TYPE_XML) { "Not a binary XML file" }

        val poolSize = parseStringPool(8)

        var offset = 8 + poolSize
        while (offset + 8 <= original.size) {
            val type = u16(offset)
            val size = u32(offset + 4)
            if (size <= 0 || offset + size > original.size) break
            if (type == TYPE_START_ELEMENT) {
                chunks.add(parseElement(offset))
            } else {
                chunks.add(original.copyOfRange(offset, offset + size))
            }
            offset += size
        }
    }

    private fun parseStringPool(off: Int): Int {
        val headerSize = u16(off + 2)
        val size = u32(off + 4)
        val count = u32(off + 8)
        val flags = u32(off + 16)
        val stringsStart = u32(off + 20)
        val utf8 = (flags and UTF8_FLAG) != 0
        val offsetsBase = off + headerSize

        strings.clear()
        for (i in 0 until count) {
            val so = u32(offsetsBase + i * 4)
            val p = off + stringsStart + so
            strings.add(if (utf8) decodeUtf8(p) else decodeUtf16(p))
        }
        return size
    }

    private fun decodeUtf8(start: Int): String {
        var p = start
        var len = u8(p)
        if (len and 0x80 != 0) {
            len = ((len and 0x7F) shl 8) or u8(p + 1); p += 2
        } else p += 1
        var blen = u8(p)
        if (blen and 0x80 != 0) {
            blen = ((blen and 0x7F) shl 8) or u8(p + 1); p += 2
        } else p += 1
        return String(original, p, blen, Charsets.UTF_8)
    }

    private fun decodeUtf16(start: Int): String {
        var p = start
        var len = u16(p); p += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or u16(p); p += 2
        }
        return String(original, p, len * 2, Charsets.UTF_16LE)
    }

    private fun parseElement(off: Int): Element {
        val headerSize = u16(off + 2)
        val base = off + headerSize
        val ns = u32(base)
        val name = u32(base + 4)
        val attrStart = u16(base + 8)
        val attrSize = u16(base + 10)
        val attrCount = u16(base + 12)
        // attrStart نسبةً إلى بنية attrExt (التي تبدأ عند off + headerSize)
        val attrsBase = off + headerSize + attrStart

        val attrs = ArrayList<Attr>(attrCount)
        for (i in 0 until attrCount) {
            val p = attrsBase + i * attrSize
            attrs.add(
                Attr(
                    ns = u32(p),
                    name = u32(p + 4),
                    rawValue = u32(p + 8),
                    valueType = u8(p + 15),
                    valueData = u32(p + 16),
                )
            )
        }
        return Element(ns, name, attrs)
    }

    // --------------------------------------------------------------- helpers

    private fun u8(p: Int): Int = original[p].toInt() and 0xFF
    private fun u16(p: Int): Int = u8(p) or (u8(p + 1) shl 8)
    private fun u32(p: Int): Int = u16(p) or (u16(p + 2) shl 16)

    private fun indexOfString(s: String): Int = strings.indexOf(s)

    private fun addString(s: String): Int {
        val existing = strings.indexOf(s)
        if (existing >= 0) return existing
        strings.add(s)
        return strings.size - 1
    }

    private fun elementName(e: Element): String =
        if (e.name in strings.indices) strings[e.name] else ""

    private fun attrName(a: Attr): String =
        if (a.name in strings.indices) strings[a.name] else ""

    private fun attrValue(a: Attr): String? = when {
        a.valueType == TYPE_STRING && a.valueData in strings.indices -> strings[a.valueData]
        a.rawValue in strings.indices -> strings[a.rawValue]
        else -> null
    }

    private fun setAttrString(a: Attr, value: String) {
        val index = addString(value)
        a.valueType = TYPE_STRING
        a.valueData = index
        // مهم: محلّل أندرويد يقرأ القيمة الخام (raw) لسمات النصوص، لذا نضبطها أيضًا.
        a.rawValue = index
    }

    // ------------------------------------------------------------ public API

    /** أقل إصدار Android يدعمه التطبيق (من <uses-sdk>)، أو null إن لم يُذكر. */
    fun minSdkVersion(): Int? {
        val usesSdk = chunks.filterIsInstance<Element>().firstOrNull { elementName(it) == "uses-sdk" }
            ?: return null
        val value = usesSdk.attrs.firstOrNull { attrName(it) == "minSdkVersion" } ?: return null
        return value.valueData
    }

    fun packageName(): String? {
        val manifest = chunks.filterIsInstance<Element>().firstOrNull { elementName(it) == "manifest" }
            ?: return null
        return manifest.attrs.firstOrNull { attrName(it) == "package" }?.let { attrValue(it) }
    }

    /**
     * يجهّز المانيفست لنسخة جديدة (الحزمة الأساسية فقط):
     *  - إعادة تسمية الحزمة وكل ما يرتبط بها.
     *  - extractNativeLibs=true.
     *  - إضافة صلاحيات إضافية إن وُجدت (الافتراضي لا شيء).
     *  - حقن ContentProvider لتشغيل وحدة الموقع الوهمي.
     */
    fun patchForClone(
        oldPkg: String,
        newPkg: String,
        injectProviderClass: String,
        providerAuthority: String,
        extraPermissions: List<String> = emptyList(),
    ) {
        renamePackage(oldPkg, newPkg)
        setExtractNativeLibs()
        addUsesPermissions(extraPermissions)
        addInjectProvider(injectProviderClass, providerAuthority)
        addQueriesProvider(HOST_LOCATION_AUTHORITY)
    }

    /** يُصرّح للنسخة برؤية مزوّد ABUJHAN (رؤية الحزم في أندرويد 11+). */
    private fun addQueriesProvider(authority: String) {
        val manifestIndex = chunks.indexOfFirst { it is Element && elementName(it) == "manifest" }
        if (manifestIndex < 0) return

        val queriesIndex = addString("queries")
        val providerIndex = addString("provider")
        chunks.add(manifestIndex + 1, Element(-1, queriesIndex, arrayListOf()))
        chunks.add(manifestIndex + 2, Element(-1, providerIndex, arrayListOf(stringAttr("authorities", authority))))
        chunks.add(manifestIndex + 3, EndElement(-1, providerIndex))
        chunks.add(manifestIndex + 4, EndElement(-1, queriesIndex))
    }

    /** يعيد تسمية الحزمة وكل ما يرتبط بها دون أي حقن (يُستخدم أيضًا لحِزم التقسيم). */
    fun renamePackage(oldPkg: String, newPkg: String) {
        chunks.filterIsInstance<Element>().firstOrNull { elementName(it) == "manifest" }
            ?.attrs?.firstOrNull { attrName(it) == "package" }
            ?.let { setAttrString(it, newPkg) }

        for (chunk in chunks) {
            if (chunk !is Element) continue
            val name = elementName(chunk)
            val isComponent = name in COMPONENT_TAGS
            val isPermissionTag = name in PERMISSION_TAGS

            val toRemove = ArrayList<Attr>()
            for (a in chunk.attrs) {
                val an = attrName(a)
                when {
                    an == "sharedUserId" -> toRemove.add(a)

                    // اسم الحزمة يُعالَج أعلاه؛ نتجنّب تطبيق قاعدة البادئة عليه مرّتين.
                    an == "package" && name == "manifest" -> Unit

                    an == "name" && isComponent ->
                        attrValue(a)?.let { setAttrString(a, resolveComponentName(it, oldPkg)) }

                    an == "name" && isPermissionTag ->
                        attrValue(a)?.let { setAttrString(a, renamePrefix(it, oldPkg, newPkg)) }

                    an == "authorities" ->
                        attrValue(a)?.let { setAttrString(a, renameAuthority(it, oldPkg, newPkg)) }

                    an == "targetPackage" || an == "permission" ->
                        attrValue(a)?.let { setAttrString(a, renamePrefix(it, oldPkg, newPkg)) }
                }
            }
            chunk.attrs.removeAll(toRemove)
        }
    }

    /** يجعل مكتبات .so تُستخرج عند التثبيت (يلزم بعد إعادة ضغط الحزمة). */
    fun setExtractNativeLibs() {
        val app = chunks.filterIsInstance<Element>().firstOrNull { elementName(it) == "application" }
            ?: return
        val existing = app.attrs.firstOrNull { attrName(it) == "extractNativeLibs" }
        if (existing != null) {
            existing.valueType = TYPE_INT_BOOLEAN
            existing.valueData = 0xFFFFFFFF.toInt() // true
            existing.rawValue = -1
        } else {
            app.attrs.add(
                Attr(androidNs, addString("extractNativeLibs"), -1, TYPE_INT_BOOLEAN, 0xFFFFFFFF.toInt())
            )
        }
    }

    private fun addUsesPermissions(permissions: List<String>) {
        if (permissions.isEmpty()) return
        val manifestIndex = chunks.indexOfFirst { it is Element && elementName(it) == "manifest" }
        if (manifestIndex < 0) return

        var insertAt = manifestIndex + 1
        for (permission in permissions) {
            val alreadyThere = chunks.filterIsInstance<Element>().any {
                elementName(it) == "uses-permission" &&
                    it.attrs.any { a -> attrName(a) == "name" && attrValue(a) == permission }
            }
            if (alreadyThere) continue

            val nameIndex = addString("uses-permission")
            chunks.add(insertAt++, Element(-1, nameIndex, arrayListOf(stringAttr("name", permission))))
            chunks.add(insertAt++, EndElement(-1, nameIndex))
        }
    }

    private fun addInjectProvider(providerClass: String, authority: String) {
        val appIndex = chunks.indexOfFirst { it is Element && elementName(it) == "application" }
        if (appIndex < 0) return

        val nameIndex = addString("provider")
        val provider = Element(
            ns = -1,
            name = nameIndex,
            attrs = arrayListOf(
                stringAttr("name", providerClass),
                stringAttr("authorities", authority),
                boolAttr("exported", false),
                boolAttr("enabled", true),
            ),
        )
        chunks.add(appIndex + 1, provider)
        chunks.add(appIndex + 2, EndElement(ns = -1, name = nameIndex))
    }

    private fun stringAttr(name: String, value: String): Attr {
        val valueIndex = addString(value)
        return Attr(
            ns = androidNs,
            name = addString(name),
            rawValue = valueIndex,
            valueType = TYPE_STRING,
            valueData = valueIndex,
        )
    }

    private fun boolAttr(name: String, value: Boolean) = Attr(
        ns = androidNs,
        name = addString(name),
        rawValue = -1,
        valueType = TYPE_INT_BOOLEAN,
        valueData = if (value) 0xFFFFFFFF.toInt() else 0,
    )

    private fun renamePrefix(value: String, oldPkg: String, newPkg: String): String =
        if (value.startsWith("$oldPkg.")) newPkg + value.substring(oldPkg.length) else value

    private fun renameAuthority(value: String, oldPkg: String, newPkg: String): String {
        // قد تحوي عدّة معرّفات مفصولة بـ ;
        return value.split(';').joinToString(";") { renamePrefix(it.trim(), oldPkg, newPkg) }
    }

    /**
     * أسماء المكوّنات يجب أن تبقى مُشيرة إلى أصناف الكود الأصلية، لأنّنا لا نغيّر
     * أسماء الأصناف داخل dex. لذلك نحوّل الأسماء النسبية إلى أسماء مطلقة باسم
     * الحزمة الأصلية، ونترك الأسماء المطلقة كما هي.
     */
    private fun resolveComponentName(value: String, oldPkg: String): String = when {
        value.startsWith(".") -> oldPkg + value
        !value.contains(".") -> "$oldPkg.$value"
        else -> value
    }

    // -------------------------------------------------------------- serialize

    fun toBytes(): ByteArray {
        val out = ByteArrayOutputStream(original.size + 512)
        val pool = buildStringPool()

        // header مؤقت (نصحّح الحجم لاحقًا)
        putU16(out, TYPE_XML)
        putU16(out, 8)
        putU32(out, 0)
        out.write(pool)

        for (chunk in chunks) {
            when (chunk) {
                is ByteArray -> out.write(chunk)
                is Element -> out.write(serializeElement(chunk))
                is EndElement -> out.write(serializeEndElement(chunk))
            }
        }

        val bytes = out.toByteArray()
        writeU32(bytes, 4, bytes.size)
        return bytes
    }

    private fun buildStringPool(): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for (i in strings.indices) {
            offsets[i] = data.size()
            val s = strings[i]
            val bytes = s.toByteArray(Charsets.UTF_8)
            writeLen8(data, s.length)
            writeLen8(data, bytes.size)
            data.write(bytes)
            data.write(0)
        }
        val dataBytes = data.toByteArray()
        // يجب أن يكون حجم قطعة جدول النصوص من مضاعفات 4 (شرط AXML).
        val padding = (4 - (dataBytes.size % 4)) % 4

        val headerSize = 28
        val offsetsSize = strings.size * 4
        val stringsStart = headerSize + offsetsSize
        val total = stringsStart + dataBytes.size + padding

        val out = ByteArrayOutputStream(total)
        putU16(out, TYPE_STRING_POOL)
        putU16(out, headerSize)
        putU32(out, total)
        putU32(out, strings.size)
        putU32(out, 0) // styleCount
        putU32(out, UTF8_FLAG)
        putU32(out, stringsStart)
        putU32(out, 0) // stylesStart
        for (o in offsets) putU32(out, o)
        out.write(dataBytes)
        repeat(padding) { out.write(0) }
        return out.toByteArray()
    }

    private fun writeLen8(out: ByteArrayOutputStream, v: Int) {
        if (v > 0x7F) {
            out.write((v shr 8) or 0x80)
            out.write(v and 0xFF)
        } else {
            out.write(v)
        }
    }

    private fun serializeElement(e: Element): ByteArray {
        val size = 16 + 20 + e.attrs.size * 20
        val out = ByteArrayOutputStream(size)
        putU16(out, TYPE_START_ELEMENT)
        putU16(out, 16)
        putU32(out, size)
        putU32(out, -1) // lineNumber
        putU32(out, -1) // comment
        putU32(out, e.ns)
        putU32(out, e.name)
        putU16(out, 20) // attributeStart
        putU16(out, 20) // attributeSize
        putU16(out, e.attrs.size)
        putU16(out, 0) // idIndex
        putU16(out, 0) // classIndex
        putU16(out, 0) // styleIndex
        for (a in e.attrs) {
            putU32(out, a.ns)
            putU32(out, a.name)
            putU32(out, a.rawValue)
            putU16(out, 8) // typedValue size
            out.write(0)   // res0
            out.write(a.valueType)
            putU32(out, a.valueData)
        }
        return out.toByteArray()
    }

    private fun serializeEndElement(e: EndElement): ByteArray {
        val out = ByteArrayOutputStream(24)
        putU16(out, TYPE_END_ELEMENT)
        putU16(out, 16)
        putU32(out, 24)
        putU32(out, -1)
        putU32(out, -1)
        putU32(out, e.ns)
        putU32(out, e.name)
        return out.toByteArray()
    }

    private fun writeU32(bytes: ByteArray, pos: Int, v: Int) {
        bytes[pos] = (v and 0xFF).toByte()
        bytes[pos + 1] = ((v ushr 8) and 0xFF).toByte()
        bytes[pos + 2] = ((v ushr 16) and 0xFF).toByte()
        bytes[pos + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun putU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun putU32(out: ByteArrayOutputStream, v: Int) {
        putU16(out, v and 0xFFFF)
        putU16(out, (v ushr 16) and 0xFFFF)
    }

    // ------------------------------------------------------------- inner types

    private class Attr(
        var ns: Int,
        var name: Int,
        var rawValue: Int,
        var valueType: Int,
        var valueData: Int,
    )

    private class Element(val ns: Int, val name: Int, val attrs: ArrayList<Attr>)
    private class EndElement(val ns: Int, val name: Int)

    private companion object {
        const val TYPE_XML = 0x0003
        const val TYPE_STRING_POOL = 0x0001
        const val TYPE_START_ELEMENT = 0x0102
        const val TYPE_END_ELEMENT = 0x0103

        const val UTF8_FLAG = 0x00000100
        const val TYPE_STRING = 0x03
        const val HOST_LOCATION_AUTHORITY = "com.abujhan.container.location"
        const val TYPE_INT_BOOLEAN = 0x12

        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        val COMPONENT_TAGS = setOf(
            "application", "activity", "activity-alias", "service", "receiver", "provider",
        )
        val PERMISSION_TAGS = setOf(
            "permission", "uses-permission", "permission-group", "permission-tree",
        )
    }
}

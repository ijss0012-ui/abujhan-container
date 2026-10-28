package com.abujhan.container.clone

import android.content.Context
import com.abujhan.container.clone.axml.AxmlEditor
import com.abujhan.container.data.AppEntry
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** ناتج الاستنساخ: الحزمة الأساسية + حِزم التقسيم (إن وُجدت). */
class CloneOutput(val base: File, val splits: List<File>) {
    val all: List<File> get() = listOf(base) + splits
}

/**
 * محرّك الاستنساخ:
 *
 *  1) يقرأ كل مُدخلات APK الأصلي (الأساسية + التقسيمات).
 *  2) يعدّل AndroidManifest.xml في كل ملف (اسم حزمة جديد).
 *     - في الحزمة الأساسية فقط: يحقن مزوّد التهيئة ويصرّح برؤية مزوّد موقع ABUJHAN.
 *  3) يحقن classesN.dex (وحدة الموقع) + ملف الإعدادات في الحزمة الأساسية.
 *  4) يعيد بناء كل الحِزم ثم يوقّعها.
 */
class CloneEngine(private val context: Context) {

    /** يُنشئ اسم حزمة فريدًا للنسخة. */
    fun nextContainerPackage(originalPackage: String): String {
        val pm = context.packageManager
        var index = 1
        while (true) {
            val candidate = if (index == 1) "$originalPackage.clone"
            else "$originalPackage.clone$index"
            val installed = runCatching { pm.getPackageInfo(candidate, 0) }.isSuccess
            if (!installed) return candidate
            index++
        }
    }

    fun build(entry: AppEntry, newPackage: String): CloneOutput {
        val workDir = File(context.filesDir, "clones").apply { mkdirs() }

        val base = processApk(
            source = File(entry.apkPath),
            oldPkg = entry.packageName,
            newPkg = newPackage,
            isBase = true,
            fileLabel = "base",
            workDir = workDir,
        )

        val splits = entry.splitPaths.mapIndexed { index, path ->
            processApk(
                source = File(path),
                oldPkg = entry.packageName,
                newPkg = newPackage,
                isBase = false,
                fileLabel = "split$index",
                workDir = workDir,
            )
        }

        return CloneOutput(base, splits)
    }

    private fun processApk(
        source: File,
        oldPkg: String,
        newPkg: String,
        isBase: Boolean,
        fileLabel: String,
        workDir: File,
    ): File {
        val t0 = System.nanoTime()
        val entries = Zip.read(source)
        val tRead = System.nanoTime()
        val output = ArrayList<Zip.Entry>(entries.size + 2)
        var sourceMinSdk: Int? = null
        val cached = InjectionCache.load(context, oldPkg, source)
        android.util.Log.i("CloneEngine", "scan cache ${if (cached != null) "hit" else "miss"}: ${source.name}")
        val scanned = HashMap<String, Int>()
        val dexRewrites = rewriteDexEntries(entries, source, oldPkg, cached)

        var maxDex = 1
        for (e in entries) {
            val m = DEX_REGEX.find(e.name) ?: continue
            val n = m.groupValues[1].let { if (it.isEmpty()) 1 else it.toIntOrNull() ?: 1 }
            if (n > maxDex) maxDex = n
        }
        val injectDexName = "classes${maxDex + 1}.dex"

        for (e in entries) {
            when {
                isSignatureFile(e.name) -> Unit // نتخلّص من التوقيع القديم

                e.name == "AndroidManifest.xml" -> {
                    val editor = AxmlEditor(e.data)
                    sourceMinSdk = editor.minSdkVersion()
                    if (isBase) {
                        editor.patchForClone(
                            oldPkg = oldPkg,
                            newPkg = newPkg,
                            injectProviderClass = INJECT_PROVIDER,
                            providerAuthority = "$newPkg.inject",
                            extraPermissions = EXTRA_PERMISSIONS,
                        )
                    } else {
                        editor.renamePackage(oldPkg, newPkg)
                    }
                    output.add(Zip.Entry("AndroidManifest.xml", Zip.DEFLATED, editor.toBytes()))
                }

                e.name == "resources.arsc" -> {
                    val patched = e.data.copyOf()
                    ArscPatcher.renamePackage(patched, newPkg)
                    output.add(Zip.Entry(e.name, Zip.STORED, patched))
                }

                DEX_REGEX.matches(e.name) && PATCH_SYSTEM_EXIT -> {
                    // إعادة الكتابة أولًا على الملف الأصلي (لتفادي إفساد الترميز بالتعديل البايتي)،
                    // ثم تعطيل System.exit على الناتج.
                    val (rewritten, hooks) = dexRewrites.getValue(e.name)
                    scanned[e.name] = hooks
                    if (hooks > 0) {
                        android.util.Log.i("CloneEngine", "hooked $hooks location call(s) in ${e.name}")
                    }
                    val count = DexPatcher.noOpSystemExit(rewritten)
                    if (count > 0) {
                        android.util.Log.i("CloneEngine", "disabled $count System.exit call(s) in ${e.name}")
                    }
                    output.add(Zip.Entry(e.name, Zip.DEFLATED, rewritten))
                }

                // تطبيقات Xamarin/Mono تقرأ assemblies.blob مباشرة من الـ APK، فيجب أن يبقى غير مضغوط.
                e.name.startsWith("assemblies/") -> output.add(Zip.Entry(e.name, Zip.STORED, e.data))

                else -> output.add(Zip.Entry(e.name, Zip.DEFLATED, e.data))
            }
        }

        if (cached == null) {
            InjectionCache.save(context, oldPkg, source, scanned)
        }
        val tProcess = System.nanoTime()

        if (isBase) {
            val injectDex = context.assets.open(INJECT_DEX_ASSET).use { it.readBytes() }
            val config = JSONObject()
                .put("package", newPkg)
                .put("originalPackage", oldPkg)
                .put("hostPackage", context.packageName)
                .toString()
                .toByteArray(Charsets.UTF_8)
            output.add(Zip.Entry(injectDexName, Zip.DEFLATED, injectDex))
            output.add(Zip.Entry("assets/abujhan_inject.json", Zip.DEFLATED, config))
        }

        val unsigned = File(workDir, "$newPkg.$fileLabel.unsigned.apk")
        val signed = File(workDir, "$newPkg.$fileLabel.apk")
        unsigned.delete()
        signed.delete()

        Zip.write(unsigned, output)
        val tWrite = System.nanoTime()
        val signMinSdk = sourceMinSdk ?: DEFAULT_MIN_SDK
        ApkSignerHelper.sign(context, unsigned, signed, minSdk = signMinSdk)
        val tSign = System.nanoTime()
        fun ms(a: Long, b: Long) = (b - a) / 1_000_000
        android.util.Log.i(
            "CloneTiming",
            "${source.name}: read=${ms(t0, tRead)}ms process=${ms(tRead, tProcess)}ms " +
                "write=${ms(tProcess, tWrite)}ms sign=${ms(tWrite, tSign)}ms size=${source.length()}",
        )
        unsigned.delete()
        return signed
    }

    /**
     * يعالج ملفات dex بالتوازي (حتى DEX_THREADS ملفات في وقت واحد).
     * كل ملف مستقل عن الآخر، لذا النتيجة لا تتغيّر، والزمن يقلّ.
     */
    private fun rewriteDexEntries(
        entries: List<Zip.Entry>,
        source: File,
        oldPkg: String,
        cached: Map<String, Int>?,
    ): Map<String, Pair<ByteArray, Int>> {
        val dexEntries = entries.filter { DEX_REGEX.matches(it.name) }
        if (dexEntries.isEmpty()) return emptyMap()
        val pool = Executors.newFixedThreadPool(DEX_THREADS)
        try {
            val futures = dexEntries.map { e ->
                e.name to pool.submit(Callable { rewriteDex(e, source, oldPkg, cached) })
            }
            return futures.associate { (name, future) -> name to future.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun rewriteDex(
        e: Zip.Entry,
        source: File,
        oldPkg: String,
        cached: Map<String, Int>?,
    ): Pair<ByteArray, Int> {
        val known = cached?.get(e.name)
        // ملف معروف أنه بلا نداءات موقع: لا حاجة لفحصه.
        if (known == 0) return e.data to 0
        // ناتج محفوظ لهذا الإصدار والمحوّل: لا حاجة لإعادة الكتابة.
        if (known != null) {
            InjectionCache.loadRewritten(context, oldPkg, source, e.name)?.let { return it to known }
        }
        val started = System.nanoTime()
        val (out, hooks) = SpoofRewriter.rewrite(e.data)
        if (hooks > 0) InjectionCache.saveRewritten(context, oldPkg, source, e.name, out)
        android.util.Log.i(
            "CloneTiming",
            "${e.name}: rewrite=${(System.nanoTime() - started) / 1_000_000}ms hooks=$hooks",
        )
        return out to hooks
    }

    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val upper = name.uppercase()
        return upper.endsWith(".SF") || upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") || upper.endsWith(".EC") ||
            upper.endsWith("MANIFEST.MF")
    }

    companion object {
        const val INJECT_DEX_ASSET = "location_inject.dex"
        private const val DEX_THREADS = 2
        const val INJECT_PROVIDER = "com.container.inject.InjectProvider"
        private const val DEFAULT_MIN_SDK = 26

        /** النسخ لا تحتاج صلاحية الموقع الوهمي: المصدر الوحيد هو ABUJHAN. */
        val EXTRA_PERMISSIONS: List<String> = emptyList()

        /** تعطيل نداءات System.exit داخل النسخة (لتجاوز فحوص كشف التلاعب). */
        const val PATCH_SYSTEM_EXIT = true

        private val DEX_REGEX = Regex("^classes(\\d*)\\.dex$")
    }
}

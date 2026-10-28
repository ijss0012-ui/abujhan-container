package com.abujhan.container.clone

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * مساعد صلاحيات root (اختياري لكن مفيد جدًا).
 *
 * جهازك مروّت (Magisk)، لذلك يمكننا تلقائيًا:
 *   - منح النسخة صلاحية "مصدر موقع وهمي" (appops android:mock_location).
 *   - منح أذونات الموقع العادية.
 *   - إيقاف النسخة قسرًا قبل تشغيلها.
 *
 * إن لم يتوفّر root فسيبقى المستخدم قادرًا على اختيار النسخة يدويًا من
 * خيارات المطوّر ← تحديد تطبيق الموقع الوهمي (تظهر لأنّنا نضيف الصلاحية).
 */
object RootHelper {

    private const val TAG = "RootHelper"

    fun hasRoot(): Boolean = runCatching { exec("id").contains("uid=0") }.getOrDefault(false)

    /** يمنح النسخة صلاحية الموقع الوهمي. */
    fun grantMockLocation(containerPackage: String): Boolean {
        runCatching { exec("appops set $containerPackage android:mock_location allow") }
        val state = runCatching { exec("appops get $containerPackage android:mock_location") }
            .getOrDefault("")
        Log.i(TAG, "grantMockLocation($containerPackage) -> $state")
        return state.contains("allow")
    }

    /** يسحب صلاحية الموقع الوهمي (يُعيد النظام إلى الوضع الطبيعي). */
    fun revokeMockLocation(containerPackage: String): Boolean {
        runCatching { exec("appops set $containerPackage android:mock_location ignore") }
        Log.i(TAG, "revokeMockLocation($containerPackage)")
        return true
    }

    /** يمنح أذونات الموقع العادية (تفشل بهدوء إن لم تكن معلنة). */
    fun grantLocationPermissions(containerPackage: String) {
        runCatching {
            exec("pm grant $containerPackage android.permission.ACCESS_FINE_LOCATION")
            exec("pm grant $containerPackage android.permission.ACCESS_COARSE_LOCATION")
        }
    }

    /**
     * يخفي الروت عن النسخة عبر قائمة الحجب في Magisk (Zygisk DenyList)،
     * حتى لا تكتشف التطبيقات التي تفحص الروت.
     */
    fun hideRoot(containerPackage: String): Boolean {
        val out = runCatching {
            // بعض إصدارات Magisk تتطلّب تحديد العملية أيضًا.
            exec("magisk --denylist add $containerPackage")
            exec("magisk --denylist add $containerPackage $containerPackage")
        }.getOrDefault("")
        Log.i(TAG, "hideRoot($containerPackage) -> $out")
        return true
    }

    /** يلغي تثبيت النسخة كاملة (مع كل أجزائها وبياناتها) بدون نافذة تأكيد. */
    fun uninstallPackage(containerPackage: String): Boolean {
        val out = runCatching { exec("pm uninstall $containerPackage") }.getOrDefault("")
        Log.i(TAG, "uninstallPackage($containerPackage) -> $out")
        return out.contains("Success")
    }

    fun forceStop(containerPackage: String) {
        runCatching { exec("am force-stop $containerPackage") }
    }

    private fun exec(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val output = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                output.append(line).append('\n')
                line = reader.readLine()
            }
        }
        process.waitFor()
        return output.toString().trim()
    }
}

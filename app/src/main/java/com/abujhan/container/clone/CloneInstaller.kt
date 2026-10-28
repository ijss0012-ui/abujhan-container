package com.abujhan.container.clone

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.abujhan.container.clone.CloneProgress.Phase
import java.io.File

/**
 * يسلّم النسخة إلى مثبّت النظام عبر جلسة PackageInstaller (حزمة واحدة أو عدّة أجزاء).
 *
 * الجلسة تُرجع نتيجة التثبيت إلى CloneInstallReceiver، فنعرف متى يكتمل التثبيت أو يفشل.
 * يجب استدعاء install() من خيط خلفي لأن نسخ الملفات قد يستغرق وقتًا.
 */
object CloneInstaller {

    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_LABEL = "label"

    fun install(context: Context, apks: List<File>, label: String) {
        val installer = context.packageManager.packageInstaller
        val total = apks.sumOf { it.length() }.coerceAtLeast(1L)
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(total)

        val sessionId = installer.createSession(params)
        CloneProgress.set(CloneProgress.State(label, Phase.INSTALLING, percent = 0))
        installer.openSession(sessionId).use { session ->
            var written = 0L
            var lastReported = -1
            val buffer = ByteArray(256 * 1024)
            for (apk in apks) {
                session.openWrite(apk.name, 0, apk.length()).use { output ->
                    apk.inputStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            val percent = (written * 100 / total).toInt()
                            if (percent / 5 != lastReported / 5) {
                                lastReported = percent
                                CloneProgress.set(CloneProgress.State(label, Phase.INSTALLING, percent = percent))
                            }
                        }
                    }
                    session.fsync(output)
                }
            }

            val intent = Intent(context, CloneInstallReceiver::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_LABEL, label)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pendingIntent.intentSender)
        }
    }

    /** يُطلق التطبيق المستنسخ إن كان مثبّتًا. */
    fun launch(context: Context, containerPackage: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(containerPackage) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    fun isInstalled(context: Context, containerPackage: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(containerPackage, 0) }.isSuccess
}

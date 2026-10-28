package com.abujhan.container.clone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.abujhan.container.clone.CloneProgress.Phase

/**
 * يستقبل نتيجة كل جلسة تثبيت للنسخ (حزمة واحدة أو عدّة أجزاء)
 * ويحدّث نافذة التقدم داخل التطبيق.
 *
 * عند STATUS_PENDING_USER_ACTION يرسل النظام شاشة التأكيد في EXTRA_INTENT،
 * ويجب تشغيلها وإلا لن يتم التثبيت أبدًا.
 */
class CloneInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val label = intent.getStringExtra(CloneInstaller.EXTRA_LABEL) ?: "التطبيق"
        Log.i(TAG, "install status=$status label=$label message=$message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                CloneProgress.set(CloneProgress.State(label, Phase.WAITING_CONFIRM))
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                } else {
                    CloneProgress.set(
                        CloneProgress.State(label, Phase.FAILED, message = "تعذّر فتح شاشة التأكيد"),
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS ->
                CloneProgress.set(CloneProgress.State(label, Phase.DONE, percent = 100))

            else ->
                CloneProgress.set(
                    CloneProgress.State(label, Phase.FAILED, message = message ?: status.toString()),
                )
        }
    }

    private companion object {
        const val TAG = "CloneInstall"
    }
}

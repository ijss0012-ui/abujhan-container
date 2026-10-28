package com.abujhan.container.clone

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.abujhan.container.data.AppEntry

/** يجمع كل التطبيقات التي لها أيقونة في المشغّل (launcher). */
object InstalledApps {

    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)

        val byPackage = LinkedHashMap<String, AppEntry>()
        for (info in resolved) {
            val app = info.activityInfo?.applicationInfo ?: continue
            val pkg = app.packageName
            if (pkg == context.packageName) continue
            if (byPackage.containsKey(pkg)) continue

            val label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(pkg)
            val icon = runCatching { app.loadIcon(pm) }.getOrNull()
            val splits = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                app.splitSourceDirs?.toList() ?: emptyList()
            } else emptyList()
            val system = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            byPackage[pkg] = AppEntry(
                label = label,
                packageName = pkg,
                apkPath = app.sourceDir,
                splitPaths = splits,
                icon = icon,
                isSystem = system,
            )
        }
        return byPackage.values.sortedBy { it.label.lowercase() }
    }
}

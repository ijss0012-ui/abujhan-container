package com.abujhan.container.data

import android.graphics.drawable.Drawable

/**
 * تطبيق مثبّت على الجهاز، جاهز للاستنساخ.
 *
 * @param apkPath   مسار ملف APK الأساسي (sourceDir).
 * @param splitPaths مسارات حِزم التقسيم (split APKs) إن وُجدت.
 */
data class AppEntry(
    val label: String,
    val packageName: String,
    val apkPath: String,
    val splitPaths: List<String>,
    val icon: Drawable?,
    val isSystem: Boolean,
) {
    /** بعض التطبيقات الحديثة تأتي مقسّمة إلى عدّة APK ولا تعمل نسخة الأساس وحدها. */
    val isSplit: Boolean get() = splitPaths.isNotEmpty()
}

package com.abujhan.container.data

/**
 * مستودع (نسخة) أنشأها المستخدم من تطبيق أصلي.
 *
 * @param apkPaths مسارات ملفات APK للنسخة (الأساسية + التقسيمات).
 */
data class ContainerRecord(
    val originalPackage: String,
    val containerPackage: String,
    val label: String,
    val apkPaths: List<String>,
    var latitude: Double? = null,
    var longitude: Double? = null,
    var accuracy: Float = 5f,
    /** طُلب حذف النسخة من الجهاز ولم يكتمل بعد. */
    var pendingDelete: Boolean = false,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null
}

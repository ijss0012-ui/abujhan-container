package com.abujhan.container.location

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * ينشر الموقع الوهمي الحالي للنسخ عبر content://com.abujhan.container.location/current.
 * تقرأه نقاط الاعتراض داخل كل نسخة (LocationHook)، فلا تحتاج النسخ إلى root أو خيارات المطوّر.
 */
class LocationProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val context = context ?: return null
        val cursor = MatrixCursor(arrayOf("lat", "lng", "acc", "active"))
        val point = MockLocationStore.load(context)
        val active = MockLocationStore.isActive(context) && point != null
        cursor.addRow(
            arrayOf(
                point?.lat ?: 0.0,
                point?.lng ?: 0.0,
                point?.accuracy ?: 5f,
                if (active) 1 else 0,
            )
        )
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

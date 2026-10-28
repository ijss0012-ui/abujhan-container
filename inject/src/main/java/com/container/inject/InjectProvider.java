package com.container.inject;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * مزوّد محتوى يُضاف إلى مانيفست النسخة (عبر محرّر AXML).
 * يُنشئه النظام عند إقلاع النسخة، فيقوم بتسجيل المستقبِل وتشغيل الموقع الوهمي
 * قبل ظهور أي واجهة.
 */
public final class InjectProvider extends ContentProvider {

    public static final String TAG = "ContainerLocInject";

    @Override
    public boolean onCreate() {
        try {
            Log.i(TAG, "InjectProvider.onCreate pkg=" + getContext().getPackageName());
        } catch (Throwable ignored) {
        }
        try {
            LocationHook.init(getContext());
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

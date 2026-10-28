package com.container.inject;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** يقرأ ملف الإعدادات الذي تضيفه الحاوية المضيفة إلى النسخة. */
final class InjectConfig {

    static final String ASSET = "abujhan_inject.json";

    static JSONObject load(Context context) {
        try (InputStream in = context.getAssets().open(ASSET)) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private InjectConfig() {
    }
}

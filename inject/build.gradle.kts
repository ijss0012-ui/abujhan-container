plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.container.inject"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // للترجمة فقط: الأصناف موجودة في التطبيق المستنسخ نفسه وقت التشغيل.
    compileOnly("com.google.android.gms:play-services-location:21.3.0")
    compileOnly("com.google.android.gms:play-services-tasks:18.2.0")
}

// ---------------------------------------------------------------------------
// هذا الموديول لا يُثبَّت على حدة. أصنافه هي التي تُحقَن داخل كل نسخة:
//   - InjectProvider        : مزوّد محتوى يبدأ عند إقلاع النسخة.
//   - ApplyLocationReceiver : يستقبل الإحداثيات من الحاوية المضيفة.
//   - LocationSpoofer       : يشغّل مزوّد الموقع الوهمي.
//
// لتوليد ملف app/src/main/assets/location_inject.dex من هذه الأصناف شغّل:
//     tools\build_inject_dex.bat
// (السكربت يستخدم javac ثم d8 من أدوات بناء أندرويد.)
// ---------------------------------------------------------------------------

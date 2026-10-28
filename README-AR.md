# ABUJHAN Container — مشروع Android Studio نظيف

مشروع **جديد من الصفر** (بالإنجليزية: clean-room) يعيد تنفيذ الفكرة الأساسية لتطبيق
الحاوية/المُستنسِخ مع **تزوير الموقع**، وبدون أي نظام ترخيص أو تسجيل دخول.

> ملاحظة مهمّة: هذا **ليس** تفكيكًا (decompile) للتطبيق الأصلي. التطبيق الأصلي
> مشوَّش بالكامل (آلاف الأصناف باسم `o/*`) ولا يمكن تحويله إلى مشروع يُبنى في
> Android Studio. المشروع السابق في `Desktop\test\jadx` كان محاولة تفكيك وفشل
> بآلاف الأخطاء. هذا المشروع مكتوب يدويًا بصيغة نظيفة وقابلة للتعديل.

---

## 1) كيف يعمل التطبيق (الفكرة)

```
┌───────────────────────────── الحاوية المضيفة (هذا المشروع) ─────────────────────────────┐
│  MainActivity  ──►  قائمة التطبيقات المثبّتة                                            │
│        │                                                                               │
│        ▼                                                                               │
│  CloneEngine                                                                           │
│   1. يقرأ كل مُدخلات APK الأصلي (Zip.read)                                              │
│   2. يعدّل AndroidManifest.xml (AxmlEditor):                                            │
│        • package  →  <الأصلي>.clone                                                     │
│        • إعادة تسمية authorities والصلاحيات المخصّصة والمكوّنات النسبية                 │
│        • حذف sharedUserId + إضافة extractNativeLibs=true                                │
│        • حقن <provider android:name="com.container.inject.InjectProvider">              │
│   3. يحقن classesN.dex  (وحدة الموقع)  +  assets/abujhan_inject.json                    │
│   4. يعيد بناء الحزمة (Zip.write مع محاذاة resources.arsc)                              │
│   5. يوقّعها (ApkSignerHelper ← مفتاح assets/clone_key.p12)                             │
│        │                                                                               │
│        ▼                                                                               │
│  CloneInstaller ──► مثبّت النظام (ACTION_VIEW، لا تثبيت صامت)                            │
└───────────────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────── النسخة (التطبيق المستنسخ) ──────────────────────────┐
│  عند الإقلاع:                                                                │
│   • النظام يُنشئ InjectProvider (من classesN.dex المُحقَن)                    │
│   • يسجّل ApplyLocationReceiver ديناميكيًا                                    │
│   • LocationSpoofer يشغّل مزوّد موقع وهمي (gps/network/fused)                 │
│                                                                              │
│  الحاوية ترسل الإحداثيات:                                                    │
│   Intent("com.container.inject.APPLY_LOCATION").setPackage(<النسخة>)          │
└──────────────────────────────────────────────────────────────────────────────┘
```

**نقاط مهمّة:**
- لا يوجد خادم ترخيص ولا تسجيل دخول. كل شيء محلي.
- النسخ تُثبَّت عبر **مثبّت النظام**.
- على الأجهزة المروّتة تمنح الحاوية تلقائيًا صلاحية الموقع الوهمي للنسخة
  عبر `appops set <package> android:mock_location allow`.

---

## 2) الأدوات المستخدمة

| الأداة | الاستخدام |
|---|---|
| **Android Studio** | فتح المشروع وتعديل الكود وبناء APK |
| **JDK 17+** | متطلَّب لبناء أندرويد (Android Studio يوفّر JBR) |
| **Android SDK 35** | compileSdk/targetSdk |
| **javac + d8** (build-tools) | توليد ملف `location_inject.dex` (سكربت `tools/build_inject_dex.bat`) |
| **keytool** | توليد مفتاح توقيع النسخ (موجود مسبقًا في `assets/clone_key.p12`) |
| **adb** | تثبيت واختبار على الجهاز (اختياري) |

---

## 3) البنية

```
abujhan-container/
├─ settings.gradle.kts
├─ build.gradle.kts                ← نسخ AGP / Kotlin
├─ gradle.properties
├─ local.properties                ← مسار SDK (خاص بجهازك)
├─ app/                            ← الحاوية المضيفة (التطبيق الذي يُثبَّت)
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ assets/
│     │  ├─ clone_key.p12          ← مفتاح توقيع النسخ
│     │  └─ location_inject.dex    ← وحدة الحقن (مولَّدة)
│     ├─ res/                      ← الواجهة (strings/colors/layouts)
│     └─ java/com/abujhan/container/
│        ├─ MainActivity.kt        ← الواجهة والمنطق
│        ├─ data/                  ← سجلات الحاويات + التخزين
│        ├─ clone/
│        │  ├─ CloneEngine.kt      ← محرّك الاستنساخ
│        │  ├─ axml/AxmlEditor.kt  ← محرّر AndroidManifest الثنائي
│        │  ├─ Zip.kt              ← قارئ/كاتب ZIP مع محاذاة
│        │  ├─ ApkSignerHelper.kt  ← التوقيع
│        │  ├─ KeystoreManager.kt
│        │  ├─ CloneInstaller.kt   ← تثبيت عبر مثبّت النظام
│        │  ├─ InstalledApps.kt    ← قائمة التطبيقات
│        │  └─ RootHelper.kt       ← صلاحيات root الاختيارية
│        └─ location/LocationController.kt
├─ inject/                         ← أصناف تُحقَن داخل النسخة (لا تُثبَّت وحدها)
│  └─ src/main/java/com/container/inject/
│     ├─ InjectProvider.java
│     ├─ ApplyLocationReceiver.java
│     ├─ LocationSpoofer.java
│     └─ InjectConfig.java
└─ tools/build_inject_dex.bat      ← يولّد location_inject.dex من وحدة الحقن
```

---

## 4) البناء والتشغيل

### من Android Studio
1. `File ▸ Open` ثم اختر مجلد `abujhan-container`.
2. انتظر مزامنة Gradle.
3. اضغط **Run ▶**.

### من سطر الأوامر
```bat
cd abujhan-container
gradlew.bat assembleDebug
:: الناتج: app\build\outputs\apk\debug\app-debug.apk
```

### توليد وحدة الحقن (بعد تعديل أصناف `inject/`)
```bat
tools\build_inject_dex.bat
:: يكتب app\src\main\assets\location_inject.dex
```

### الاختبارات
```bat
gradlew.bat :app:testDebugUnitTest
```
- `AxmlEditorTest` يتحقّق من قراءة/تعديل مانيفست ثنائي حقيقي.
- `ClonePipelineTest` يبني نسخة موقّعة كاملة من APK مرجعي (يُتخطّى إن لم يوجد الملف).

---

## 5) كيف تعدّل بسهولة

| ماذا تريد | الملف |
|---|---|
| اسم التطبيق / النصوص العربية | `app/src/main/res/values/strings.xml` |
| الألوان | `app/src/main/res/values/colors.xml` |
| تخطيط الواجهة | `app/src/main/res/layout/*.xml` |
| لاحقة اسم حزمة النسخة (`<الأصلي>.clone`) | `CloneEngine.nextContainerPackage()` |
| آلية التزوير | `inject/src/main/java/com/container/inject/LocationSpoofer.java` |
| منطق الاستنساخ | `app/src/main/java/com/abujhan/container/clone/CloneEngine.kt` |
| محرّر المانيفست | `.../clone/axml/AxmlEditor.kt` |
| مفتاح التوقيع | استبدل `assets/clone_key.p12` (وتحدّث كلمة المرور في `KeystoreManager`) |

---

## 6) القيود الحالية (مهمّة)

هذا مشروع نظيف وقابل للتعديل، وليس نسخة مطابقة للتطبيق الأصلي. أهم الفروق:

1. **التطبيقات المقسّمة (split APKs):** تُستنسخ حزمة الأساس فقط. التطبيقات
   الكبيرة (مثل إنستغرام/يوتيوب) قد لا تعمل. التوسيع: استخدام `PackageInstaller`
   بجلسة متعدّدة الأجزاء.
2. **الموقع الوهمي:** يعتمد على مزوّد `LocationManager`. التطبيقات التي تستخدم
   `FusedLocationProviderClient` (خدمات Google) قد تتجاوز المزوّد الوهمي.
   التوسيع: حقن أعمق أو hooking.
3. **كود النسخة:** لا نستبدل صنف `Application` الأصلي (لتفادي كسر التطبيقات التي
   تعمل `(MyApp) getApplication()`)، بل نضيف `ContentProvider` — وهو أأمن.
4. **التوقيع:** النسخ موقّعة بمفتاحنا؛ التطبيقات التي تعتمد على توقيعها الأصلي
   (خدمات Google، فحوص التوقيع) قد تفشل.
5. **الصلاحيات المخصّصة بامتداد التوقيع:** قد لا تُمنح للنسخة.
6. **بدون root:** يجب اختيار النسخة يدويًا كمصدر موقع وهمي من خيارات المطوّر.

---

## 7) ملاحظات أمنية

- المفتاح `clone_key.p12` وكلمة مروره داخل التطبيق — مخصّص للاستخدام الشخصي فقط.
- لا ترسل هذه الحزمة إلى متجر Play (سياسات الاستنساخ/الموقع الوهمي تمنع ذلك).
- استخدم المشروع على تطبيقاتك وعلى جهازك فقط.

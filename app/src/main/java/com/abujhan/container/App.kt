package com.abujhan.container

import android.app.Application

/**
 * التطبيق الرئيسي (الحاوية/المُستنسِخ).
 * لا يوجد هنا أي منطق ترخيص — التطبيق يعمل محليًا بالكامل.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

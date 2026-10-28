// Root build file. Plugins are declared here (with `apply false`) so every module
// can use the same versions.
plugins {
    id("com.android.application") version "8.10.1" apply false
    id("com.android.library") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}

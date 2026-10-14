plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { buildFeatures { aidl=true }; namespace="dev.wearbrdgeadb"; compileSdk=28
 defaultConfig { applicationId="dev.wearbrdgeadb"; minSdk=26; targetSdk=28; versionCode=19; versionName="1.17" }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_1_8; targetCompatibility=JavaVersion.VERSION_1_8 }
 kotlinOptions { jvmTarget="1.8" }
}
dependencies {
 implementation("com.google.android.gms:play-services-wearable:20.0.1")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
 implementation("dev.rikka.shizuku:api:13.1.5")
 implementation("dev.rikka.shizuku:provider:13.1.5")
}

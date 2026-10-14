plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace="dev.wearbrdgeadb"; compileSdk=28
 defaultConfig { applicationId="dev.wearbrdgeadb"; minSdk=24; targetSdk=28; versionCode=34; versionName="1.32" }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_1_8; targetCompatibility=JavaVersion.VERSION_1_8 }
 kotlinOptions { jvmTarget="1.8" }
}
dependencies {
 implementation("com.google.android.gms:play-services-wearable:20.0.1")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}

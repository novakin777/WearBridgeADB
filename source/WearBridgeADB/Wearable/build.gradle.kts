plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val requestTraceEnabled=providers.gradleProperty("wearbridgeTrace").orNull=="true"
android { buildFeatures { aidl=true; buildConfig=true }; namespace="dev.wearbrdgeadb"; compileSdk=28
 defaultConfig { applicationId="dev.wearbrdgeadb"; minSdk=26; targetSdk=28; versionCode=35; versionName="1.33"; buildConfigField("boolean","REQUEST_TRACE_ENABLED",requestTraceEnabled.toString()) }
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

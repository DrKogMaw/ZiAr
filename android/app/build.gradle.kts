plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.ziar.transfer"; compileSdk = 35
    defaultConfig { applicationId = "com.ziar.transfer"; minSdk = 26; targetSdk = 35; versionCode = 3; versionName = "0.2.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies { testImplementation("junit:junit:4.13.2") }

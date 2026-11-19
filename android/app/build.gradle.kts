import java.io.FileInputStream
import java.security.KeyStore

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val signingStore = System.getenv("ZIAR_SIGNING_STORE")
val signingPassword = System.getenv("ZIAR_SIGNING_PASSWORD")

android {
    namespace = "com.ziar.transfer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ziar.transfer"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.4.2"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        if (!signingStore.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
            create("ziarStable") {
                storeFile = file(signingStore)
                storePassword = signingPassword
                storeType = "PKCS12"
                val keyStore = KeyStore.getInstance("PKCS12")
                FileInputStream(signingStore).use { keyStore.load(it, signingPassword.toCharArray()) }
                keyAlias = keyStore.aliases().toList().first { keyStore.isKeyEntry(it) }
                keyPassword = signingPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (!signingStore.isNullOrBlank() && !signingPassword.isNullOrBlank()) signingConfig = signingConfigs.getByName("ziarStable")
        }
    }
}

kotlin { jvmToolchain(17) }
dependencies {
    implementation("androidx.core:core:1.15.0")
    testImplementation("junit:junit:4.13.2")
}

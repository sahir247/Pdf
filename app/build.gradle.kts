plugins {
    id("com.android.application")
}

android {
    namespace = "dev.sahir.pdfinspector"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.sahir.pdfinspector"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE.md",
                // BouncyCastle 1.85 jars all carry these same resources.
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        // pdfbox-android brings BC 1.72 transitively. Keep one BC family/version
        // on the runtime classpath; the app uses the explicitly pinned 1.85 artifacts below.
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcutil-jdk15to18")
    }
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sowz.ffxstudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sowz.ffxstudio"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // Bu uygulamada OAuth/login yok (sow7rix-studio'nun aksine), bu yuzden
            // sabit bir keystore'a ihtiyac yok - CI'nin otomatik ürettiği debug
            // imzasıyla assembleDebug kullanılıyor; gercek dagitim icin kullanici
            // kendi keystore'unu ekleyebilir.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    packaging { resources.excludes += setOf("META-INF/*.version", "META-INF/DEPENDENCIES") }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    // Render motoru: ffx'ten cikarilan efekt zincirinin yakin FFmpeg esdegerini
    // uygulayip gercek video/foto ciktisi uretir. sow7rix-studio'da ayni surumle
    // dogrulanmis, aktif bakimi yapilan topluluk forku (orijinal com.arthenica
    // paketi Maven Central'dan kaldirildigi icin).
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.mas.dastyar"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.mas.dastyar"
        // اندروید ۶.۰ — پایین‌ترین سطحی که منطقی است:
        //  • مجوزهای زمان‌اجرا (runtime permissions) از همین API معرفی شدند و
        //    کل منطق PermissionsGate بر آن استوار است.
        //  • موتور گفتار eSpeak NG هم دقیقاً از اندروید ۶.۰ به بالا کار می‌کند.
        minSdk = 23
        targetSdk = 34
        versionCode = 8
        versionName = "0.7.0-offline"

        vectorDrawables {
            useSupportLibrary = true
        }

        // فقط دو معماری رایج، تا کتابخانه نیتیو Vosk حجم اپ را چند برابر نکند.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // فایل‌های مدل تشخیص گفتار نباید دوباره فشرده شوند؛ باز کردنشان
    // هنگام اولین اجرا این‌طور سریع‌تر و مطمئن‌تر است.
    androidResources {
        noCompress.addAll(
            listOf("mdl", "conf", "fst", "int", "ie", "txt", "dubm", "dic", "carpa", "vec", "mat")
        )
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // تشخیص گفتار آفلاین
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}

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
        versionCode = 6
        versionName = "0.5.0-debug"

        vectorDrawables {
            useSupportLibrary = true
        }
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
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.routetracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.routetracker"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation("androidx.fragment:fragment:1.8.2")
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.play.services.location)
    implementation(libs.osmdroid.android)
    implementation(libs.core.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

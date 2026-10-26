plugins { id("com.android.application") }
android {
    namespace = "org.motoai.baseline"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    defaultConfig {
        applicationId = "org.motoai.baseline"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

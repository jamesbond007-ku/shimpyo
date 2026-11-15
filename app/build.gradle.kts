import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.time.LocalDate

// 구글 지도 키(Google Map Tiles API). 저장소에 올리지 않도록 local.properties(.gitignore 대상)에서만 읽는다.
//   MAPS_API_KEY=AIza...
// 키가 없거나 거절되면 오픈스트리트맵 지도로, 인터넷이 없으면 개요도로 동작한다.
val mapsApiKey: String = Properties().run {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    getProperty("MAPS_API_KEY") ?: System.getenv("MAPS_API_KEY") ?: ""
}.trim().replace("\"", "")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// OneDrive 같은 동기화 폴더 안에서는 빌드 중 파일이 잠겨 "Failed to delete some children"이 날 수 있다.
// 그럴 때는 -PshimpyoBuildDir=C:\dev\shimpyo-build 처럼 빌드 결과를 동기화 밖에 둔다(지정하지 않으면 기본 위치).
(findProperty("shimpyoBuildDir") as String?)?.let { layout.buildDirectory.set(file(it)) }

android {
    namespace = "com.pitstop.shimpyo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pitstop.shimpyo"
        minSdk = 28 // Pleos Connect 요구사항: Android API 28 이상
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        // 화면에 보이는 빌드 표시: git 커밋(없으면 nogit) + 빌드 날짜. 팀원끼리 같은 판인지 확인용.
        // git이 PATH에 없는 윈도우(Android Studio 기본)도 있어 설치 경로도 찾아본다.
        val commit = listOf("git", "C:/Program Files/Git/cmd/git.exe").firstNotNullOfOrNull { git ->
            runCatching {
                val p = ProcessBuilder(git, "rev-parse", "--short", "HEAD").directory(rootDir).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText().trim().takeIf { p.waitFor() == 0 && it.isNotEmpty() }
            }.getOrNull()
        } ?: "nogit"
        val day = LocalDate.now().toString()
        buildConfigField("String", "BUILD_ID", "\"$commit · $day\"")
    }

    // AAOS Car API(GEAR_SELECTION, PARKING_BRAKE_ON). 컴파일은 SDK의 optional android.car.jar로,
    // 런타임 구현은 차량 OS(Pleos Connect)가 제공한다.
    useLibrary("android.car")

    // 제출용 두 가지 판
    //  - demo: 시연 도구(시연 배속·대답 버튼·가상 주행·워치/캘린더 시연값·adb 시연 명령)가 들어 있는 시연판
    //  - live: 시연 기능을 모두 뺀 실제 작동판. 차량·내비·음성 신호로만 움직이고, 설정·기록·SDK 진단만 남는다.
    flavorDimensions += "mode"
    productFlavors {
        create("demo") {
            dimension = "mode"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            resValue("string", "app_name", "쉼표 시연")
            buildConfigField("boolean", "DEMO_TOOLS", "true")
        }
        create("live") {
            dimension = "mode"
            resValue("string", "app_name", "쉼표")
            buildConfigField("boolean", "DEMO_TOOLS", "false")
        }
    }

    buildTypes {
        release {
            // Pleos SDK와 kotlin-reflect 호출을 보존하기 위해 시제품 단계에서는 축소하지 않는다.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)
    implementation(libs.kotlin.reflect)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlinSerialization)
    application
}

val jvm = libs.versions.jvm.get()

java {
    sourceCompatibility = JavaVersion.toVersion(jvm)
    targetCompatibility = JavaVersion.toVersion(jvm)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(jvm)
    }
}

// ponytail: engine, packs 모듈은 Android, iOS 대상만 있어서 JVM 도구가 의존하지 못한다. 그래서 HTTP 호출과 응답 모양을 여기서 따로 둔다.
// 시험 팩의 응답 클래스를 바꾸면 Gemini.kt 의 스키마도 같이 고친다. 어긋나는 일이 생기면 packs 에 jvm() 대상을 더하고 이 모듈이 의존하게 한다.
dependencies {
    implementation(project(":libs:live-gemini"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.testJunit)
}

application {
    mainClass = "io.github.nosorae.aiopic.tools.eval.MainKt"
}

// 프롬프트 파일과 evals/ 를 저장소 루트 기준 경로로 읽고 쓴다.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

// 코치 테스트가 시험 팩의 실제 지시, 도구 선언 파일과 대본을 읽는다.
tasks.test {
    workingDir = rootDir
}

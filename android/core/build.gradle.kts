plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.json)
    testImplementation(libs.kotlin.test)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:${libs.versions.kotlin.get()}")
    testImplementation(libs.json)
}

tasks.test {
    useJUnitPlatform()
}

sourceSets {
    named("test") {
        resources.srcDir(rootProject.projectDir.resolve("../test-vectors"))
    }
}

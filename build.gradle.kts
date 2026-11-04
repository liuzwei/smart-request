import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.smartrequest"
version = "1.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val localIdePath = providers.gradleProperty("localIdePath").orNull

dependencies {
    intellijPlatform {
        if (localIdePath != null) {
            local(localIdePath)
        } else {
            // Keep this in sync with the IDE version used for development.
            intellijIdea("2026.2.2")
        }
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
        zipSigner()
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.13.4")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.test {
    useJUnitPlatform()
}

// Ship the license and disclaimer with the plugin distribution, as required by Apache-2.0.
tasks.prepareSandbox {
    from("LICENSE") { into("smart-request") }
    from("NOTICE") { into("smart-request") }
    from("DISCLAIMER.md") { into("smart-request") }
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.smartrequest.idea"
        name = "Smart Request"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }

        vendor {
            name = "liuzhaowei"
            url = "https://github.com/liuzwei/smart-request"
        }
    }
}

import com.adarshr.gradle.testlogger.TestLoggerExtension
import com.adarshr.gradle.testlogger.theme.ThemeType.STANDARD
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library") version "9.1.0" apply false
    id("com.adarshr.test-logger") version "4.0.0" apply false
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
    kotlin("jvm") version "2.3.20" apply false
}

allprojects {
    pluginManager.apply("com.adarshr.test-logger")

    tasks.withType(JavaCompile::class.java).configureEach {
        sourceCompatibility = JavaVersion.VERSION_17.toString()
        targetCompatibility = JavaVersion.VERSION_17.toString()
    }

    tasks.withType(KotlinCompile::class.java).configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            optIn.add("kotlin.ExperimentalUnsignedTypes")
        }
    }

    configure<TestLoggerExtension> {
        theme = STANDARD
        showExceptions = true
        showStackTraces = false
        showFullStackTraces = false
        showCauses = true
        slowThreshold = 5000
        showSummary = true
        showSimpleNames = false
        showPassed = true
        showSkipped = true
        showFailed = true
        showOnlySlow = false
        showStandardStreams = false
        showPassedStandardStreams = false
        showSkippedStandardStreams = false
        showFailedStandardStreams = true
    }
}

plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "dadb.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 23

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments +=
                    listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DANDROID_PLATFORM=android-23",
                    )
            }
        }
    }

    buildFeatures {
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path =
                project.layout.projectDirectory
                    .file("src/main/cpp/CMakeLists.txt")
                    .asFile
            version = "3.22.1"
        }
    }
}

dependencies {
    api(project(":dadb"))
    implementation("org.bouncycastle:bcpkix-jdk18on:1.83")
    implementation("io.github.vvb2060.ndk:boringssl:20251124")
    //noinspection Aligned16KB
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20251224")
}

mavenPublishing {
    publishToMavenCentral(true)
}

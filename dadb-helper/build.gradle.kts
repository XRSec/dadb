import java.util.Properties
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Zip

plugins {
    `java-library`
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

val androidSdkDir =
    sequenceOf(
        providers.environmentVariable("ANDROID_HOME").orNull,
        providers.environmentVariable("ANDROID_SDK_ROOT").orNull,
        localProperties.getProperty("sdk.dir"),
    ).firstOrNull { !it.isNullOrBlank() }
        ?: error("Android SDK not found. Set ANDROID_HOME, ANDROID_SDK_ROOT, or sdk.dir in local.properties")

val buildToolsDir =
    file("$androidSdkDir/build-tools")
        .listFiles()
        ?.filter { it.isDirectory }
        ?.maxByOrNull { it.name }
        ?: error("Android build-tools not found under $androidSdkDir/build-tools")

val androidJar = file("$androidSdkDir/platforms/android-36/android.jar")
val d8Executable = File(buildToolsDir, "d8")

dependencies {
    compileOnly(files(androidJar))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val helperClassesJar =
    tasks.named<Jar>("jar") {
        archiveBaseName.set("dadb-icon-helper-classes")
        manifest {
            attributes["Main-Class"] = "dadb.helper.AppIconExportMain"
        }
    }

val dexOutputDir = layout.buildDirectory.dir("intermediates/dadb-helper-d8")

val dexHelperClasses by tasks.registering(Exec::class) {
    dependsOn(helperClassesJar)
    inputs.file(helperClassesJar.flatMap { it.archiveFile })
    outputs.dir(dexOutputDir)
    doFirst {
        delete(dexOutputDir)
        dexOutputDir.get().asFile.mkdirs()
    }
    commandLine(
        d8Executable.absolutePath,
        "--min-api",
        "23",
        "--lib",
        androidJar.absolutePath,
        "--output",
        dexOutputDir.get().asFile.absolutePath,
        helperClassesJar.get().archiveFile.get().asFile.absolutePath,
    )
}

tasks.register<Zip>("dexJar") {
    dependsOn(dexHelperClasses)
    from(dexOutputDir)
    include("classes.dex")
    archiveBaseName.set("dadb-icon-helper")
    archiveExtension.set("jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
}

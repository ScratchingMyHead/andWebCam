plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from the (untracked, gitignored) local.properties:
// release.storeFile / release.storePassword / release.keyAlias /
// release.keyPassword. The keystore itself lives in keystore/, which is ignored
// too. Keep both: without them later releases cannot update an installed copy.
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.forEachLine { line ->
        val t = line.trim()
        if (t.isNotEmpty() && !t.startsWith("#") && "=" in t) {
            project.extra.set(t.substringBefore("="), t.substringAfter("="))
        }
    }
}

// Every build is identifiable in the page footer, so a report of "the rate is
// wrong" can be tied to an exact commit.
fun git(vararg args: String): String = try {
    val p = ProcessBuilder(listOf("git") + args).directory(rootDir).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor()
    out.lineSequence().firstOrNull().orEmpty()
} catch (e: Exception) {
    ""
}

val buildId = git("rev-parse", "--short", "HEAD").ifEmpty { "unknown" } +
    (if (git("status", "--porcelain").isNotEmpty()) "-dirty" else "")

android {
    namespace = "net.andwebcam"
    compileSdk = 34

    signingConfigs {
        create("release") {
            storeFile = project.findProperty("release.storeFile")?.let { file(it as String) }
            storePassword = project.findProperty("release.storePassword") as String?
            keyAlias = project.findProperty("release.keyAlias") as String?
            keyPassword = project.findProperty("release.keyPassword") as String?
        }
    }

    defaultConfig {
        applicationId = "net.andwebcam"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "1.0"
        buildConfigField("String", "BUILD_ID", "\"$buildId\"")
    }

    buildTypes {
        // Debug builds are what we sideload during development.
        release {
            isMinifyEnabled = false
            // Signed only when local.properties carries the release key;
            // without it the release APK stays unsigned.
            if (project.hasProperty("release.storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // No AndroidX / no extra dependencies: keeps the download small on a slow link.
    dependenciesInfo {
        includeInApk = false
    }
}

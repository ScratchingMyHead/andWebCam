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

// Versioning: the base name and code are bumped by hand when a new major or
// minor series starts; the patch level is a per-checkout monotonic counter in
// .buildcounter (gitignored, so every checkout numbers its own builds) that
// advances on every Gradle run, debug or release alike. Every build is thus
// an installable upgrade of every earlier one, and the running app always
// says which build it is - in the page footer, the status endpoint, and the
// startup log line. At a base bump, delete .buildcounter to restart the
// patch at 1 if you like. The counter is capped well below the 1000-step
// headroom so a base bump cannot collide with a live patch.
val versionBase = "1.5"
val versionBaseCode = 12
val counterFile = rootProject.file(".buildcounter")
val patch = (counterFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0) + 1
check(patch < 1000) {
    "the $versionBase patch level has reached $patch; bump versionBase/versionBaseCode and reset .buildcounter"
}
counterFile.writeText(patch.toString())

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
        versionCode = versionBaseCode * 1000 + patch
        versionName = "$versionBase.$patch"
        buildConfigField("String", "BUILD_ID", "\"$buildId\"")
        // Only the phone this is built for is arm64. Keeping one ABI means
        // the OpenCV SDK adds about 10 MB instead of over 100 MB.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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

    // No AndroidX. The only dependencies are OpenCV (loaded when the object
    // detector is selected) and OkHttp (the outbound feed's WebSocket client).
    dependenciesInfo {
        includeInApk = false
    }
}

dependencies {
    implementation("org.opencv:opencv:4.12.0")
    // WebSocket client for the outbound public feed. Not AndroidX.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

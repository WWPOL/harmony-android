import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The app's version, in two parts that answer two different questions.
//
// VERSION is the release line: a number a person reads, changed on purpose when the app
// reaches a milestone, and the name of a release when the commit carrying it is tagged
// vX.Y.Z. It is not bumped per commit, and it does not identify a build.
//
// A build is identified by its commit. A branch build's version name is
// `<VERSION>-<sha7>[-debug]` and its versionCode is a build number derived from the number
// of commits reachable from it, so two commits never report the same build, a later commit
// always reports a higher code than an earlier one, and nobody maintains a counter.
//
// A tag build is the release named by VERSION: it reports the plain version (`0.1.0`), so
// a release is one named point rather than one of a stream of builds.
//
// -PharmonyVersionCode overrides the code; upgrade-test.sh uses it to build one tree twice
// with a higher code, which is what a later commit would produce.
val appVersionLine: String = rootProject.file("VERSION").readText().trim()
run {
    val parts = appVersionLine.split('.')
    require(parts.size == 3 && parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() } }) {
        "VERSION is not x.y.z: " + appVersionLine
    }
}

// The commit this build is of, read from git rather than from an environment variable: a
// local build and a CI build of one commit then report the same build. `providers.exec` is
// the configuration-cache-compatible way to run git here; Gradle records its output as a
// build configuration input, so a build of a different commit reconfigures instead of
// replaying the previous commit's version.
fun gitOutput(vararg arguments: String): String? = try {
    providers.exec {
        commandLine(listOf("git") + arguments)
        workingDir(rootProject.projectDir)
    }.standardOutput.asText.get().trim().ifEmpty { null }
} catch (_: Exception) {
    // No git, or not a checkout: there is no commit to name.
    null
}

val gitCommit: String = gitOutput("rev-parse", "HEAD") ?: "unknown"
val gitCommitShort: String = gitCommit.take(7)
// `rev-list --count HEAD` is the number of commits reachable from this one: it only grows
// along a branch, and every build of one commit gets the same number. A CI checkout has to
// fetch the whole history for it to mean that (`fetch-depth: 0` in android.yml).
val gitCommitCount: Int = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull()?.coerceAtLeast(1) ?: 1

// The first build under this scheme has to install over the last one built under the old
// one, which numbered a release maj*1e6 + min*1e3 + pat: 1.9.6 was 1_009_006, and no 1.x
// release could exceed 1_999_999. Starting the build number above that keeps the change of
// scheme from being a downgrade Android refuses. It is chosen once and never changes
// again, because the commit count only grows.
val BUILD_NUMBER_BASE = 2_000_000

val versionCodeOverride: Int? = providers.gradleProperty("harmonyVersionCode").orNull?.toIntOrNull()
val appVersionCode: Int = versionCodeOverride ?: (BUILD_NUMBER_BASE + gitCommitCount)

// A branch build says which commit it is; a tag build (`-PharmonyReleaseBuild`, set by
// release.yml) says which release. The debug variant appends -debug after this, so the
// About screen and the APK's file name both name the build that is installed.
val isTagBuild: Boolean = providers.gradleProperty("harmonyReleaseBuild").orNull == "true"
val appVersionName: String = if (isTagBuild) appVersionLine else "$appVersionLine-$gitCommitShort"

// Production signing is configured outside Git: keystore.properties (gitignored, in this
// directory) names the store and its passwords, and CI writes it from secrets. Without it a release build is test-signed and its published
// file name carries -debug, so a test-signed APK is never passed off as a production one.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystorePropertiesFile.isFile &&
    !keystoreProperties.getProperty("storeFile").isNullOrBlank()

// The debug key is otherwise generated per checkout, and a generated key has a random
// certificate: two CI runs, or a CI run and a local build, would each sign with a
// different key, and Android refuses to install one over the other. A CI secret can
// supply one stable test key instead (test-keystore.properties, written by the workflow
// from ANDROID_TEST_KEYSTORE_BASE64). It is a test key either way: it
// is never a release key, and an APK it signs is still named -debug.
val testKeystorePropertiesFile = rootProject.file("test-keystore.properties")
val testKeystoreProperties = Properties().apply {
    if (testKeystorePropertiesFile.isFile) testKeystorePropertiesFile.inputStream().use { load(it) }
}
val hasTestSigning = testKeystorePropertiesFile.isFile &&
    !testKeystoreProperties.getProperty("storeFile").isNullOrBlank()

android {
    namespace = "lol.mony.harmony"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.wwpol.brapmony"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        fun literal(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        buildConfigField("String", "DEFAULT_INSTANCE_URL", literal(providers.gradleProperty("harmonyInstanceUrl").getOrElse("")))
        buildConfigField("String", "DEFAULT_INSTANCE_ANON_KEY", literal(providers.gradleProperty("harmonyAnonKey").getOrElse("")))
    }

    // A test key, never a release key: the stable one when the build has it, otherwise
    // the conventional debug key generated into ignored project state. Either way an APK
    // it signs is named -debug.
    signingConfigs.getByName("debug") {
        if (hasTestSigning) {
            storeFile = rootProject.file(testKeystoreProperties.getProperty("storeFile"))
            storePassword = testKeystoreProperties.getProperty("storePassword")
            keyAlias = testKeystoreProperties.getProperty("keyAlias")
            keyPassword = testKeystoreProperties.getProperty("keyPassword")
        } else {
            storeFile = rootProject.file(".gradle/debug.keystore")
        }
    }

    // Configured only when keystore.properties exists, so a checkout without production
    // signing still builds (test-signed) instead of failing on a missing key.
    if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Falls back to the test key rather than leaving the APK unsigned: an
            // unsigned release APK cannot be installed at all, and a file that cannot be
            // installed is not a build anybody can try. The name carries -debug.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
        // One package for every build. A debug APK and a release APK are the same app, so an
        // installed build is updated in place rather than leaving two apps side by side; what
        // decides whether Android accepts the update is the signing certificate, not the
        // build type (see docs/builds.md). The version name still says which build it is.
        debug {
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
    }
}

// AGP creates only its default user-home debug store automatically. A confined build's
// user-home can be ephemeral, so create the conventional debug key in ignored project state.
val debugStore = rootProject.layout.projectDirectory.file(".gradle/debug.keystore")
val createLocalDebugKeystore = tasks.register<Exec>("createLocalDebugKeystore") {
    val destination = debugStore.asFile
    val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
    commandLine("${System.getProperty("java.home")}/bin/keytool$suffix", "-genkeypair", "-noprompt",
        "-keystore", destination.absolutePath, "-storepass", "android", "-keypass", "android",
        "-alias", "androiddebugkey", "-dname", "CN=Android Debug,O=Android,C=US",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000")
    outputs.file(destination)
    onlyIf { !destination.exists() }
}
tasks.matching { it.name.startsWith("validateSigningDebug") }.configureEach {
    if (!hasTestSigning) dependsOn(createLocalDebugKeystore)
}

// A release build signed with the fallback test key still has to find that key.
tasks.matching { it.name.startsWith("validateSigningRelease") }.configureEach {
    if (!hasReleaseSigning && !hasTestSigning) dependsOn(createLocalDebugKeystore)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    // Window size classes and the list-detail scaffold the shell's wide layout is built on.
    // Both are stable releases managed by the same Compose BOM as material3.
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)
    // LiveKit is the transport Harmony's own voice stack speaks; the web client uses its JS
    // SDK against the same rooms, so a call placed here is a call the web client can hear.
    implementation(libs.livekit.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    // The headless Compose render harness draws real composables through Robolectric's native
    // graphics so a screen's pixels can be captured without an emulator.
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

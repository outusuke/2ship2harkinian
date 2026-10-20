import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val repositoryRoot: File = rootProject.projectDir.parentFile

// One source of truth for the version: the CMake project declaration.
val projectVersion: String =
    Regex("""project\s*\([^)]*VERSION\s+([0-9.]+)""")
        .find(File(repositoryRoot, "CMakeLists.txt").readText())
        ?.groupValues?.get(1)
        ?: "0.0.0"

// Release signing: android/key.properties locally, the environment on CI. The
// file holds passwords, so it is gitignored and never read into the build
// output.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

// With neither configured — a fork's CI, or a fresh clone — the release build
// falls back to the debug key so it still produces an installable APK rather
// than failing on a keystore nobody has.
val hasReleaseKeystore =
    keystoreProperties.getProperty("storeFile") != null || System.getenv("KEYSTORE_FILE") != null

// ZAPD runs on the device to turn the player's ROM into mm.o2r, and it reads its
// recipes (xml, file lists, symbol maps) off the filesystem. They ride into the
// APK in one zip alongside the engine archive; the launcher unpacks it into the
// game directory on first run (see GameAssets.kt). The digest beside it is what
// the launcher compares to decide whether to unpack again.
abstract class PackGameData : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val extractorAssets: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val xmlAssets: DirectoryProperty

    // A collection rather than an InputFile so a missing archive reaches the
    // explanation below instead of Gradle's own input-validation failure.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val engineArchive: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val engine = engineArchive.files.singleOrNull()?.takeIf(File::isFile)
            ?: throw GradleException(
                "2ship.o2r not found at ${engineArchive.asPath} - build the Generate2ShipOtr " +
                    "target on a desktop platform (or download the CI artifact) before building the APK."
            )

        val entries = buildList {
            add(engine.name to engine)
            val extractorRoot = extractorAssets.get().asFile
            extractorRoot.walkTopDown().filter(File::isFile).forEach {
                add("assets/${it.relativeTo(extractorRoot).invariantSeparatorsPath}" to it)
            }
            val xmlRoot = xmlAssets.get().asFile
            xmlRoot.walkTopDown().filter(File::isFile).forEach {
                add("assets/xml/${it.relativeTo(xmlRoot).invariantSeparatorsPath}" to it)
            }
        }.sortedBy { it.first }

        val target = outputDir.get().asFile
        target.mkdirs()
        val zipFile = File(target, "gamedata.zip")

        // Sorted, at a fixed timestamp: a reproducible zip means a stable digest,
        // so an app update only re-unpacks on the device when the contents changed.
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            entries.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name).apply { time = FIXED_ENTRY_TIME })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        val digest = MessageDigest.getInstance("MD5").digest(zipFile.readBytes())
        File(target, "gamedata.version").writeText(digest.joinToString("") { "%02x".format(it) })
    }

    private companion object {
        // 1980-02-01T00:00:00Z; a zip entry's DOS timestamp can't go earlier than 1980.
        const val FIXED_ENTRY_TIME = 318211200000L
    }
}

// Wired through the variant API rather than added as a source-set srcDir: the
// asset merge takes the path but not the dependency on the task that fills it,
// so a clean checkout would package an APK with missing assets.
androidComponents {
    onVariants { variant ->
        val packGameData =
            tasks.register<PackGameData>("pack${variant.name.replaceFirstChar(Char::titlecase)}GameData") {
                description = "Bundles the ZAPD recipes and the engine archive into the APK."
                extractorAssets.set(File(repositoryRoot, "mm/assets/extractor"))
                xmlAssets.set(File(repositoryRoot, "mm/assets/xml"))
                engineArchive.from(File(repositoryRoot, "2ship.o2r"))
            }
        variant.sources.assets?.addGeneratedSourceDirectory(packGameData, PackGameData::outputDir)
    }
}

android {
    namespace = "com.twoship.android"
    compileSdk = 36
    ndkVersion = providers.gradleProperty("ndkVersion").orElse("30.0.16248370").get()

    defaultConfig {
        applicationId = "com.twoship.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = projectVersion

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DUSE_OPENGLES=ON",
                    "-DSDL_SHARED=ON",
                    "-DSDL_STATIC=OFF",
                    "-DHAVE_LD_VERSION_SCRIPT=OFF",
                    "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
                    // Forced for every variant, debug included. The root
                    // CMakeLists tunes CMAKE_C_FLAGS_RELEASE and pairs it with
                    // -fno-strict-aliasing for the game's decompiled C; the
                    // Android plugin would otherwise build the release variant
                    // as RelWithDebInfo, which is a flag set the project never
                    // tunes and which bloats the .so with debug info the
                    // symbol-table upload already covers.
                    "-DCMAKE_BUILD_TYPE=Release"
                )
                targets += "2ship"
            }
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                val configuredStore = keystoreProperties.getProperty("storeFile")
                if (configuredStore != null) {
                    // A relative path in key.properties reads against the file's
                    // own directory, which is what someone editing it would
                    // expect.
                    storeFile = rootProject.file(configuredStore)
                    storePassword = keystoreProperties.getProperty("storePassword")
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                } else {
                    storeFile = file(System.getenv("KEYSTORE_FILE"))
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        debug {
            // No isJniDebuggable: the native side is built Release regardless
            // (see the CMAKE_BUILD_TYPE argument above), so there would be
            // nothing useful to attach to.
            applicationIdSuffix = ".debug"
        }
        release {
            // The APK is almost entirely native code and assets, so shrinking
            // the tiny Kotlin layer buys nothing and only risks stripping the
            // classes the JNI bridge looks up by name.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = File(repositoryRoot, "CMakeLists.txt")
            // libultraship needs CMake >= 3.26; the NDK-bundled 3.22.1 is too old.
            version = "3.30.3+"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // The on-screen controller layout (res/layout/touchcontrol_overlay.xml).
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}

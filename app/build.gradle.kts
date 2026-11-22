plugins {
    id("tvbro.android.application")
    alias(libs.plugins.ksp)
}

//Wisp's own build number (the phone app counts from 1; Lumen's is versionCode below).
//The release workflow reads this line: keep the form "val wispVersionCode = <number>".
val wispVersionCode = 21

android {
    namespace = "com.phlox.tvwebbrowser"

    defaultConfig {
        applicationId = "com.sofabrowse.tv"
        versionCode = 147
        versionName = "2.0.0 Lumen (based on TV Bro 2.1.6)"

        //which native libraries go into the APK is set per app below (Lumen: both ARM kinds,
        //Wisp: 32-bit ARM only)

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf(
                    "room.incremental" to "true",
                    //used when AppDatabase @Database annotation exportSchema = true. Useful for migrations
                    "room.schemaLocation" to "$projectDir/schemas"
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            //The signing key comes from the repository's secrets (the workflow writes it to a file and
            //sets KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD). It must always be the same
            //key, otherwise Android refuses to install updates.
            val keystorePath = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
            val local = rootProject.file("signing/sofabrowse.jks")
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "sofabrowse"
                keyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: System.getenv("KEYSTORE_PASSWORD")
            } else if (local.exists() && System.getenv("KEYSTORE_PASSWORD") != null) {
                //a copy of the key in signing/ (not in the source any more), passwords from the environment
                storeFile = local
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "sofabrowse"
                keyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: System.getenv("KEYSTORE_PASSWORD")
            } else {
                logger.warn("No signing key: set the KEYSTORE secrets (see .github/workflows). Release builds will fail to package.")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            //drop resources nothing uses (smaller APK)
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = project.hasProperty("enableAbiSplits")
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    flavorDimensions += listOf("appstore", "webengine")
    productFlavors {
        //TVs, sticks and tablets are ARM: leave out the x86 copies of the native libraries
        //(ad blocker, WireGuard) - a smaller APK and less storage used
        create("generic") {
            dimension = "appstore"
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "true")
            buildConfigField("Boolean", "IS_WISP", "false")
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        }
        create("google") {
            dimension = "appstore"
            //now auto-update violates Google Play policies
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "false")
            buildConfigField("Boolean", "IS_WISP", "false")
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        }
        create("foss") {
            dimension = "appstore"
            applicationIdSuffix = ".foss"
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "false")
            buildConfigField("Boolean", "IS_WISP", "false")
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        }
        //Wisp: the light, private phone browser built from the same code. Its own app (installs
        //next to Lumen), touch UI with the bar at the bottom, no Google Play services. 32- and 64-bit ARM:
        //as a 32-bit app it ran out of memory with many tabs, and newer phones/tablets run 32-bit code
        //only on their slow cores (or not at all)
        //Build: ./gradlew assembleWispGeckoExcludedRelease  (the release workflow reads wispVersionCode at the top)
        create("wisp") {
            dimension = "appstore"
            applicationId = "com.sofabrowse.wisp"
            versionCode = wispVersionCode
            versionName = "1.6.5 Wisp"
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "false")
            buildConfigField("Boolean", "IS_WISP", "true")
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        }

        create("geckoIncluded") {
            dimension = "webengine"
            minSdk = 26 // geckoview requires minSdk 26
        }
        create("geckoExcluded") {
            dimension = "webengine"
        }
    }

    compileOptions {
        //needed by the WireGuard tunnel library
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":app:common"))
    //Firefox's engine only in "geckoIncluded" builds (tested in builds 131-135: heavy on TVs and
    //tablets; Lumen's builds leave it out to stay small)
    "geckoIncludedImplementation"(project(":app:gecko"))

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    //VPN: reads WireGuard QR codes from pictures (ZXing, Apache 2.0; no Google services)
    implementation("com.google.zxing:core:3.5.3")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.androidx.room.compiler)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    implementation(libs.segmented.button)
    implementation(libs.ad.block)
    implementation(libs.pinned.section.listview)

    //SofaBrowse: native 4K/HDR player and WireGuard VPN
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    //Chrome's network stack from Google Play services, for video servers that refuse other players.
    //Lumen only: Wisp has no Google code at all (see src/<flavor>/java/.../sofa/CronetSupport.kt)
    for (flavor in listOf("foss", "generic", "google")) {
        "${flavor}Implementation"(libs.media3.datasource.cronet)
        "${flavor}Implementation"(libs.play.services.cronet)
    }
    implementation(libs.media3.effect) //GPU video filters (Deband)
    implementation(libs.wireguard.tunnel)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
// ---------------- Lumen: adblock-rust (Brave's ad blocking engine, uBlock Origin syntax) ----------------
// Compiles app/adblock-rust for the ARM chips into src/main/jniLibs before every build.
// It never fails the build: without Rust or the Android NDK on the build machine the app is
// built without it, and Lumen keeps using its older built-in ad blocker.
val lumenRustCrate = file("adblock-rust")
val lumenJniLibs = file("src/main/jniLibs")
val lumenRustTarget = layout.buildDirectory.dir("lumenRust").get().asFile
val buildLumenAdblock = tasks.register("buildLumenAdblock") {
    group = "build"
    description = "Builds the adblock-rust library for armeabi-v7a and arm64-v8a"
    doLast {
        val home = System.getProperty("user.home")
        fun tool(name: String): String {
            val cargoHome = System.getenv("CARGO_HOME")
            listOfNotNull(cargoHome?.let { "$it/bin/$name" }, "$home/.cargo/bin/$name").forEach {
                if (File(it).canExecute()) return it
            }
            return name
        }
        fun run(cmd: List<String>, env: Map<String, String> = emptyMap()): Boolean = try {
            val pb = ProcessBuilder(cmd).directory(lumenRustCrate).redirectErrorStream(true)
            pb.environment().putAll(env)
            val proc = pb.start()
            proc.inputStream.bufferedReader().forEachLine { println("[adblock-rust] $it") }
            proc.waitFor() == 0
        } catch (e: Exception) {
            println("[adblock-rust] ${cmd.first()}: ${e.message}")
            false
        }
        fun ndkCandidates(): List<File> {
            val list = ArrayList<File>()
            listOf("ANDROID_NDK_LATEST_HOME", "ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK")
                .mapNotNull { System.getenv(it) }.forEach { list.add(File(it)) }
            listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").mapNotNull { System.getenv(it) }.forEach { sdk ->
                File(sdk, "ndk").listFiles()?.filter { it.isDirectory }
                    ?.sortedByDescending { it.name }?.forEach { list.add(it) }
                File(sdk, "ndk-bundle").let { if (it.isDirectory) list.add(it) }
            }
            return list
        }
        val bin = ndkCandidates().map { File(it, "toolchains/llvm/prebuilt/linux-x86_64/bin") }
            .firstOrNull { File(it, "aarch64-linux-android26-clang").exists() }
        if (bin == null) {
            println("[adblock-rust] Android NDK not found - building without adblock-rust")
            return@doLast
        }
        val cargo = tool("cargo")
        run(listOf(tool("rustup"), "target", "add", "aarch64-linux-android", "armv7-linux-androideabi"))
        val targets = listOf(
            Triple("aarch64-linux-android", "arm64-v8a", "aarch64-linux-android26-clang"),
            Triple("armv7-linux-androideabi", "armeabi-v7a", "armv7a-linux-androideabi26-clang")
        )
        for ((triple, abi, clang) in targets) {
            val key = triple.uppercase().replace('-', '_')
            val envVars = mapOf(
                "CARGO_TARGET_${key}_LINKER" to File(bin, clang).absolutePath,
                "CC_${triple.replace('-', '_')}" to File(bin, clang).absolutePath,
                "AR_${triple.replace('-', '_')}" to File(bin, "llvm-ar").absolutePath,
                "CARGO_TARGET_DIR" to lumenRustTarget.absolutePath
            )
            val ok = run(listOf(cargo, "build", "--release", "--target", triple), envVars)
            val so = File(lumenRustTarget, "$triple/release/liblumen_adblock.so")
            val dest = File(lumenJniLibs, "$abi/liblumen_adblock.so")
            if (ok && so.exists()) {
                dest.parentFile.mkdirs()
                so.copyTo(dest, overwrite = true)
                println("[adblock-rust] built for $abi (${so.length() / 1024} KB)")
            } else {
                println("[adblock-rust] build for $abi failed - this ABI uses the older ad blocker")
                dest.delete()
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildLumenAdblock) }

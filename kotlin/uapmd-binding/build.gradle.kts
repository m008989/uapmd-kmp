import org.gradle.api.file.FileSystemOperations
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.dokka)
}

val repoRoot = rootProject.projectDir.parentFile
val cpmCacheDir = File(System.getProperty("user.home"), ".cache/CPM/uapmd")

// Gradle 9 removed Project.exec()/Project.copy() from task actions. Ad-hoc task
// actions must obtain the corresponding services by injection instead.
interface GradleServices {
    @get:Inject val exec: ExecOperations
    @get:Inject val fs: FileSystemOperations
}
val buildServices = objects.newInstance<GradleServices>()

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.compilations["main"].cinterops {
            create("uapmd") {
                defFile(project.file("cinterop/uapmd.def"))
                includeDirs(repoRoot.resolve("c-api/include"))
            }
        }
    }

    jvm()

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    compilerOptions {
        // UInt/UByte/UShort/ULong are stable in Kotlin 2.x; suppress the opt-in noise.
        optIn.add("kotlin.ExperimentalUnsignedTypes")
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(libs.jna)
            implementation(libs.jne)
        }
        androidMain.dependencies {
            implementation(libs.oboe)
            implementation(libs.androidaudioplugin)
            implementation(libs.androidaudioplugin.ara)
            implementation(files(repoRoot.resolve("external/uapmd/android/external/SDL3-3.4.0.aar")))
        }
        jsMain.dependencies {
            implementation(libs.kotlinx.coroutinesCore)
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.coroutinesCore)
        }
    }
}

android {
    namespace = "dev.atsushieno.uapmd"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        externalNativeBuild {
            cmake {
                arguments.addAll(listOf(
                    "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                    "-DMIDICCI_SKIP_TOOLS=ON",
                    "-DCPM_SOURCE_CACHE=${cpmCacheDir.absolutePath}",
                    "-DANDROID_STL=c++_shared",
                    // No UAPMD_ENABLE_* here: cmake/UapmdFeatureOptions.cmake turns every
                    // addin on, except ARA on 32-bit ABIs (ARA_SDK's ARAInterface.h has no
                    // 32-bit ARM support). Each ABI has its own .cxx cache, so that
                    // default is per ABI; after changing an option, delete .cxx.
                    "-DAAP_DIR=placeholder"
                ))
                targets.add("uapmd-jni")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        prefab = true
    }
}

// ─── Build uapmd-c-api desktop shared library ────────────────────────────────
//
// Uses the top-level CMakeLists.txt which builds the uapmd engine from the
// submodule and then our owned c-api/ wrapper as a SHARED library.
//
// Produces:
//   cmake-build-debug/c-api/libuapmd-c-api.dylib  (macOS)
//   cmake-build-debug/c-api/libuapmd-c-api.so     (Linux)

val os           = org.gradle.internal.os.OperatingSystem.current()
val cmakeBuildDir = repoRoot.resolve("cmake-build-debug")
val cApiDylibDir  = File(cmakeBuildDir, "c-api")

tasks.register("buildUapmdCApiDesktop") {
    group       = "build"
    description = "Builds libuapmd-c-api shared library for JVM desktop via cmake"

    inputs.dir(repoRoot.resolve("c-api"))
    outputs.file(File(cApiDylibDir, when {
        os.isWindows -> "RelWithDebInfo/uapmd-c-api.dll"
        os.isMacOsX  -> "libuapmd-c-api.dylib"
        else         -> "libuapmd-c-api.so"
    }))

    doLast {
        cmakeBuildDir.mkdirs()
        // Configure if this is a fresh build directory.
        if (!File(cmakeBuildDir, "CMakeCache.txt").exists()) {
            val configureArgs = mutableListOf(
                "cmake", "-B", cmakeBuildDir.absolutePath,
                "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                "-DCPM_SOURCE_CACHE=${cpmCacheDir.absolutePath}",
                "-DUAPMD_BUILD_TESTS=OFF",
                "-DMIDICCI_SKIP_TOOLS=ON"
            )
            when {
                os.isWindows -> configureArgs.addAll(listOf("-G", "Visual Studio 18 2026"))
                os.isLinux   -> configureArgs.addAll(listOf("-G", "Ninja",
                    "-DCMAKE_C_COMPILER=clang", "-DCMAKE_CXX_COMPILER=clang++",
                    "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"))
                else         -> configureArgs.addAll(listOf("-G", "Ninja"))
            }
            buildServices.exec.exec {
                workingDir = repoRoot
                commandLine(configureArgs)
            }
        }
        buildServices.exec.exec {
            workingDir = repoRoot
            commandLine(
                "cmake", "--build", cmakeBuildDir.absolutePath,
                "--target", "uapmd-c-api",
                "--parallel"
            )
        }
    }
}

// ─── Copy dylib into JNE resource path ───────────────────────────────────────
//
// JNE looks for native libraries under jne/{os}/{arch}/{filename} in the
// classpath.  This task stages the built dylib into the jvmMain resources so
// it is included in the JAR and discoverable by JNE.loadLibrary() at runtime.

val jneArch = System.getProperty("os.arch").let {
    if (it == "aarch64") "arm64" else "x86_64"
}
val jneOs = when {
    os.isMacOsX  -> "macos"
    os.isLinux   -> "linux"
    os.isWindows -> "windows"
    else         -> "unknown"
}
// VS generator places outputs in a config subdirectory (e.g. RelWithDebInfo/); Ninja does not.
val jneLibName = when {
    os.isMacOsX  -> "libuapmd-c-api.dylib"
    os.isLinux   -> "libuapmd-c-api.so"
    else         -> "uapmd-c-api.dll"
}
val jneLibFile = if (os.isWindows) File(cApiDylibDir, "RelWithDebInfo/$jneLibName")
                 else File(cApiDylibDir, jneLibName)
val jneResourceDir = project.file("src/jvmMain/resources/jne/$jneOs/$jneArch")

tasks.register<Copy>("copyUapmdDylibToJneResources") {
    group       = "build"
    description = "Copies libuapmd-c-api into the JNE resource path for classpath discovery"
    dependsOn("buildUapmdCApiDesktop")
    from(jneLibFile)
    into(jneResourceDir)
}

// Wire: compileKotlinJvm depends on the shared library being staged in resources
afterEvaluate {
    tasks.findByName("compileKotlinJvm")?.dependsOn("copyUapmdDylibToJneResources")
    tasks.findByName("jvmProcessResources")?.dependsOn("copyUapmdDylibToJneResources")
    // AGP does not allow local .aar file dependencies when bundling a library AAR.
    // uapmd-binding is not published, so skip the AAR bundling tasks entirely.
    tasks.findByName("bundleReleaseAar")?.enabled = false
    tasks.findByName("bundleDebugAar")?.enabled = false
}

// ─── Build uapmd-c-api Emscripten Wasm module ─────────────────────────────────
//
// Produces, in this module's own build directory:
//   uapmd-binding/build/uapmd-c-api-web/uapmd-c-api.js
//   uapmd-binding/build/uapmd-c-api-web/uapmd-c-api.wasm
// plus the WebCLAP runtime assets (wclap.mjs, uapmd-wclap-host.wasm, es6/).
//
// Prerequisites: emcmake/emcc in PATH (Emscripten SDK activated).
// The output files are bundled as resources in the jsMain and wasmJsMain
// source sets so the KMP binding can load the module at runtime.

val wasmSrcDir    = project.file("src/webMain/cpp")
// Outputs live under build/ so that `gradle clean` reclaims them and git never
// sees them. They used to land in a build-wasm/ directory at the repo root,
// which survived clean and showed up untracked in every `git status`.
val wasmOutputDir = layout.buildDirectory.dir("uapmd-c-api-web").get().asFile
val wasmBuildDir  = layout.buildDirectory.dir("uapmd-c-api-wasm")
val wclapOverrideDir = repoRoot.resolve("external/uapmd/source/tools/wclap-host/web-overrides")

tasks.register("buildUapmdCApiWasm") {
    group       = "build"
    description = "Builds uapmd-c-api.js + uapmd-c-api.wasm via Emscripten"

    inputs.dir(wasmSrcDir)
    inputs.dir(repoRoot.resolve("c-api"))
    inputs.dir(repoRoot.resolve("external/uapmd/source"))
    inputs.dir(repoRoot.resolve("cmake"))
    outputs.file(File(wasmOutputDir, "uapmd-c-api.js"))
    outputs.file(File(wasmOutputDir, "uapmd-c-api.wasm"))

    doFirst {
        // Require emcmake — fail early with a clear message
        val emcmake = ProcessBuilder("which", "emcmake")
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText().trim()
        if (emcmake.isEmpty()) {
            throw GradleException(
                "emcmake not found in PATH.\n" +
                "Activate the Emscripten SDK before building:\n" +
                "  source /path/to/emsdk/emsdk_env.sh"
            )
        }
    }

    doLast {
        val buildDir = wasmBuildDir.get().asFile
        buildDir.mkdirs()
        wasmOutputDir.mkdirs()

        val cpmCacheArg = "-DCPM_SOURCE_CACHE=${cpmCacheDir.absolutePath}"

        // ── 1. Configure ──────────────────────────────────────────────────────
        buildServices.exec.exec {
            commandLine(
                "emcmake", "cmake",
                "-S", wasmSrcDir.absolutePath,
                "-B", buildDir.absolutePath,
                "-G", "Ninja",
                "-DCMAKE_BUILD_TYPE=Release",
                cpmCacheArg,
                "-DUAPMD_BUILD_TESTS=OFF",
                "-DMIDICCI_SKIP_TOOLS=ON"
            )
        }

        // ── 2. Build ──────────────────────────────────────────────────────────
        buildServices.exec.exec {
            commandLine(
                "cmake",
                "--build", buildDir.absolutePath,
                "--target", "uapmd-c-api-web", "uapmd-wclap-host",
                "--parallel"
            )
        }

        // ── 3. Copy outputs ───────────────────────────────────────────────────
        listOf("uapmd-c-api.js", "uapmd-c-api.wasm").forEach { name ->
            val built = File(buildDir, name)
            if (!built.exists()) {
                throw GradleException("Expected Emscripten output not found: ${built.absolutePath}")
            }
            built.copyTo(File(wasmOutputDir, name), overwrite = true)
        }

        val wclapHostWasm = File(buildDir, "uapmd/tools/wclap-host/uapmd-wclap-host.wasm")
        if (wclapHostWasm.exists()) {
            wclapHostWasm.copyTo(File(wasmOutputDir, "uapmd-wclap-host.wasm"), overwrite = true)
        }

        val wclapModule = fileTree(cpmCacheDir.resolve("webclap-browser-test-host")) {
            include("**/clap-audionode/wclap-js/wclap.mjs")
        }.files.singleOrNull()
            ?: throw GradleException("Unable to locate WebCLAP runtime module in $cpmCacheDir")
        wclapModule.copyTo(File(wasmOutputDir, "wclap.mjs"), overwrite = true)

        val wclapEs6Dir = wclapModule.parentFile.resolve("es6")
        if (!wclapEs6Dir.isDirectory) {
            throw GradleException("Expected WebCLAP es6 runtime directory at ${wclapEs6Dir.absolutePath}")
        }
        buildServices.fs.copy {
            from(wclapEs6Dir)
            into(File(wasmOutputDir, "es6"))
        }

        val overrideWclap = wclapOverrideDir.resolve("es6/wclap.mjs")
        if (overrideWclap.exists()) {
            overrideWclap.copyTo(File(wasmOutputDir, "es6/wclap.mjs"), overwrite = true)
        }
        logger.lifecycle("uapmd-c-api Wasm: outputs copied to $wasmOutputDir")
    }
}

// Wire: compileKotlinJs and compileKotlinWasmJs must run after the Wasm build
// so the JS/Wasm assets exist before they are bundled as resources.
afterEvaluate {
    listOf("compileKotlinJs", "compileKotlinWasmJs").forEach { taskName ->
        tasks.findByName(taskName)?.dependsOn("buildUapmdCApiWasm")
    }
}

// ── Dokka ─────────────────────────────────────────────────────────────────────
dokka {
    moduleName.set("uapmd-binding")
    dokkaSourceSets {
        // Document the shared API surface
        named("commonMain") {
            displayName.set("Common")
            reportUndocumented.set(false)
            skipDeprecated.set(false)
        }
        // Platform-specific source sets documented but grouped separately
        named("androidMain")  { displayName.set("Android") }
        named("jvmMain")      { displayName.set("JVM") }
        named("jsMain")       { displayName.set("JS") }
        named("wasmJsMain")   { displayName.set("WasmJs") }
    }
}

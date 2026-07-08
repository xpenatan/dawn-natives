import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Zip
import java.security.MessageDigest

plugins {
    base
}

data class DawnConfig(
    val repository: String,
    val revision: String
)

data class AndroidConfig(
    val ndkVersion: String,
    val minSdk: String,
    val cmakeVersion: String
)

data class PackageConfig(
    val version: String
)

data class DawnNativesConfig(
    val packageConfig: PackageConfig,
    val dawn: DawnConfig,
    val android: AndroidConfig
)

data class NativeTarget(
    val taskSuffix: String,
    val classifier: String,
    val stageDirectory: File,
    val buildDirectory: File,
    val packageFileName: String,
    val enabledOnHost: Boolean,
    val configurePrefix: List<String> = emptyList(),
    val configureArguments: List<String> = emptyList()
)

data class NativeTaskSet(
    val buildTask: TaskProvider<Exec>,
    val smokeTask: TaskProvider<Exec>,
    val packageTask: TaskProvider<Zip>
)

val configFile = layout.projectDirectory.file("dawn-natives.toml").asFile
val lockFile = layout.projectDirectory.file("dawn-natives-lock.json").asFile
val nativeProjectDir = layout.projectDirectory.dir("native")
val smokeProjectDir = layout.projectDirectory.dir("smoke")
val sourceRootDir = layout.buildDirectory.dir("sources")
val packagesDir = layout.buildDirectory.dir("packages")

fun parseTomlString(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
        return trimmed.substring(1, trimmed.length - 1)
    }
    return trimmed
}

fun readToml(): Map<String, Map<String, String>> {
    val values = linkedMapOf<String, MutableMap<String, String>>()
    var section = ""
    configFile.forEachLine { rawLine ->
        val line = rawLine.substringBefore("#").trim()
        if (line.isEmpty()) {
            return@forEachLine
        }
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.substring(1, line.length - 1).trim()
            values.computeIfAbsent(section) { linkedMapOf() }
            return@forEachLine
        }
        val separator = line.indexOf("=")
        if (separator < 0 || section.isEmpty()) {
            return@forEachLine
        }
        values.computeIfAbsent(section) { linkedMapOf() }[line.substring(0, separator).trim()] =
            line.substring(separator + 1).trim()
    }
    return values
}

fun Map<String, Map<String, String>>.required(section: String, key: String): String {
    return get(section)?.get(key)?.let(::parseTomlString)
        ?: throw GradleException("Missing dawn-natives.toml [$section].$key")
}

fun readNativeConfig(): DawnNativesConfig {
    val toml = readToml()
    return DawnNativesConfig(
        packageConfig = PackageConfig(toml.required("package", "version")),
        dawn = DawnConfig(
            repository = toml.required("dawn", "repository"),
            revision = toml.required("dawn", "revision")
        ),
        android = AndroidConfig(
            ndkVersion = toml.required("android", "ndkVersion"),
            minSdk = toml.required("android", "minSdk"),
            cmakeVersion = toml.required("android", "cmakeVersion")
        )
    )
}

val nativeConfig = readNativeConfig()

fun quoteJson(value: String): String {
    return "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"") + "\""
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun replaceTomlValues(replacements: Map<Pair<String, String>, String>) {
    val lines = configFile.readLines()
    val replaced = mutableSetOf<Pair<String, String>>()
    var section = ""
    val output = lines.map { rawLine ->
        val trimmed = rawLine.substringBefore("#").trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            section = trimmed.substring(1, trimmed.length - 1).trim()
            rawLine
        } else {
            val separator = trimmed.indexOf("=")
            if (separator < 0) {
                rawLine
            } else {
                val key = trimmed.substring(0, separator).trim()
                val replacementKey = section to key
                val replacement = replacements[replacementKey]
                if (replacement == null) {
                    rawLine
                } else {
                    replaced += replacementKey
                    "$key = ${quoteJson(replacement)}"
                }
            }
        }
    }
    val missing = replacements.keys - replaced
    if (missing.isNotEmpty()) {
        throw GradleException("Could not update dawn-natives.toml keys: ${missing.joinToString { "[${it.first}].${it.second}" }}")
    }
    configFile.writeText(output.joinToString(System.lineSeparator()) + System.lineSeparator())
}

fun writeLockManifest(config: DawnNativesConfig) {
    lockFile.writeText(
        """
        {
          "packageVersion": ${quoteJson(config.packageConfig.version)},
          "dawn": {
            "repository": ${quoteJson(config.dawn.repository)},
            "revision": ${quoteJson(config.dawn.revision)}
          },
          "android": {
            "ndkVersion": ${quoteJson(config.android.ndkVersion)},
            "minSdk": ${quoteJson(config.android.minSdk)},
            "cmakeVersion": ${quoteJson(config.android.cmakeVersion)}
          }
        }
        """.trimIndent() + System.lineSeparator()
    )
}

fun hostOs(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("windows") -> "windows"
        os.contains("linux") -> "linux"
        os.contains("mac") || os.contains("darwin") -> "macos"
        else -> "unknown"
    }
}

fun executableCommand(name: String): List<String> {
    val windows = hostOs() == "windows"
    if (!windows) {
        return listOf(name)
    }
    val path = System.getenv("PATH") ?: return listOf(name)
    val candidates = listOf("$name.exe", "$name.bat", "$name.cmd", "$name.ps1", name)
    for (entry in path.split(File.pathSeparatorChar)) {
        val directory = entry.trim().trim('"')
        if (directory.isEmpty()) {
            continue
        }
        for (candidate in candidates) {
            val file = File(directory, candidate)
            if (file.isFile) {
                return if (file.extension.equals("ps1", ignoreCase = true)) {
                    listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", file.absolutePath)
                } else {
                    listOf(file.absolutePath)
                }
            }
        }
    }
    return listOf(name)
}

fun androidNdkDirectory(): File? {
    val explicit = System.getenv("ANDROID_NDK_HOME")?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
    if (explicit != null) {
        return File(explicit).takeIf(File::isDirectory)
    }
    val androidHome = listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT")
    ).firstOrNull { it.isNotBlank() }?.trim()?.trim('"') ?: return null
    return File(androidHome, "ndk/${nativeConfig.android.ndkVersion}").takeIf(File::isDirectory)
}

fun androidTaskSuffix(abi: String): String {
    return abi.split('-', '_').joinToString("") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

val dawnSourceDir = sourceRootDir.map { it.dir("dawn") }

fun dawnGeneratorPythonPath(): String {
    val entries = mutableListOf(dawnSourceDir.get().asFile.resolve("generator").absolutePath)
    System.getenv("PYTHONPATH")?.takeIf { it.isNotBlank() }?.let(entries::add)
    return entries.joinToString(File.pathSeparator)
}

fun patchDawnGeneratorImports(source: File) {
    val marker = "sys.path.insert(0, os.path.dirname(__file__))"
    listOf(
        "dawn_json_generator.py",
        "dawn_version_generator.py",
        "dawn_gpu_info_generator.py",
        "opengl_loader_generator.py"
    ).forEach { fileName ->
        val file = source.resolve("generator/$fileName")
        if (!file.isFile) {
            throw GradleException("Dawn generator script is missing: ${file.absolutePath}")
        }
        val text = file.readText()
        if (marker in text) {
            return@forEach
        }
        val needle = "\nfrom generator_lib import"
        val index = text.indexOf(needle)
        if (index < 0) {
            throw GradleException("Could not patch Dawn generator import path in ${file.absolutePath}")
        }
        file.writeText(text.substring(0, index) + "\n$marker\n" + text.substring(index))
    }
}

val printNativeDepsConfig = tasks.register("printNativeDepsConfig") {
    group = "help"
    description = "Prints the dawn-natives dependency and toolchain pins."
    doLast {
        println("package.version=${nativeConfig.packageConfig.version}")
        println("dawn.repository=${nativeConfig.dawn.repository}")
        println("dawn.revision=${nativeConfig.dawn.revision}")
        println("android.ndkVersion=${nativeConfig.android.ndkVersion}")
        println("android.minSdk=${nativeConfig.android.minSdk}")
        println("android.cmakeVersion=${nativeConfig.android.cmakeVersion}")
    }
}

val refreshLockManifest = tasks.register("refreshLockManifest") {
    group = "dawn natives"
    description = "Writes dawn-natives-lock.json from dawn-natives.toml."
    inputs.file(configFile)
    outputs.file(lockFile)
    doLast {
        writeLockManifest(readNativeConfig())
    }
}

val resolveDawnSource = tasks.register("resolveDawnSource") {
    group = "dawn natives"
    description = "Clones or updates the pinned Dawn source under build/sources."
    inputs.property("dawnRepository", nativeConfig.dawn.repository)
    inputs.property("dawnRevision", nativeConfig.dawn.revision)
    outputs.dir(dawnSourceDir)
    doLast {
        val source = dawnSourceDir.get().asFile
        if (!source.resolve(".git").isDirectory) {
            source.parentFile.mkdirs()
            providers.exec {
                commandLine(
                    "git", "clone", "--depth", "1", "--filter=blob:none", "--sparse",
                    nativeConfig.dawn.repository, source.absolutePath
                )
            }.result.get()
        }
        providers.exec {
            workingDir = source
            commandLine("git", "config", "core.longpaths", "true")
        }.result.get()
        providers.exec {
            workingDir = source
            commandLine(
                "git", "sparse-checkout", "set", "--no-cone",
                "CMakeLists.txt", "DEPS", "cmake", "src", "include", "third_party",
                "build", "build_overrides", "generator", "tools"
            )
        }.result.get()
        providers.exec {
            workingDir = source
            commandLine("git", "fetch", "--depth", "1", "origin", nativeConfig.dawn.revision)
        }.result.get()
        providers.exec {
            workingDir = source
            commandLine("git", "reset", "--hard", "FETCH_HEAD")
        }.result.get()
        patchDawnGeneratorImports(source)
    }
}

fun registerNativeTarget(target: NativeTarget): NativeTaskSet {
    val configureTask = tasks.register<Exec>("configure${target.taskSuffix}") {
        group = "dawn natives"
        description = "Configures ${target.classifier} Dawn static native package."
        onlyIf { target.enabledOnHost }
        dependsOn(resolveDawnSource)
        inputs.file(nativeProjectDir.file("CMakeLists.txt"))
        inputs.file(nativeProjectDir.file("dawn-natives-targets.cmake.in"))
        inputs.dir(dawnSourceDir)
        outputs.dir(target.buildDirectory)
        doFirst {
            target.buildDirectory.mkdirs()
            target.stageDirectory.mkdirs()
        }
        environment("PYTHONPATH", dawnGeneratorPythonPath())
        commandLine(
            target.configurePrefix + executableCommand("cmake") + listOf(
                "-S", nativeProjectDir.asFile.absolutePath,
                "-B", target.buildDirectory.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DDAWN_NATIVES_DAWN_SOURCE_DIR=${dawnSourceDir.get().asFile.absolutePath}",
                "-DDAWN_NATIVES_STAGE_DIR=${target.stageDirectory.absolutePath}"
            ) + target.configureArguments
        )
    }
    val buildTask = tasks.register<Exec>("build${target.taskSuffix}") {
        group = "dawn natives"
        description = "Builds and stages ${target.classifier} Dawn static native package."
        onlyIf { target.enabledOnHost }
        dependsOn(configureTask)
        inputs.file(nativeProjectDir.file("CMakeLists.txt"))
        inputs.file(nativeProjectDir.file("dawn-natives-targets.cmake.in"))
        outputs.dir(target.stageDirectory)
        environment("PYTHONPATH", dawnGeneratorPythonPath())
        commandLine(
            executableCommand("cmake") + listOf(
                "--build", target.buildDirectory.absolutePath,
                "--target", "stage_dawn_natives",
                "--config", "Release",
                "--parallel", Runtime.getRuntime().availableProcessors().coerceAtMost(8).coerceAtLeast(1).toString()
            )
        )
    }
    val smokeBuildDir = layout.buildDirectory.dir("smoke/${target.classifier}").get().asFile
    val configureSmokeTask = tasks.register<Exec>("configure${target.taskSuffix}Smoke") {
        group = "verification"
        description = "Configures the ${target.classifier} dawn-natives link smoke test."
        onlyIf { target.enabledOnHost }
        dependsOn(buildTask)
        inputs.dir(target.stageDirectory)
        outputs.dir(smokeBuildDir)
        doFirst {
            smokeBuildDir.mkdirs()
        }
        commandLine(
            target.configurePrefix + executableCommand("cmake") + listOf(
                "-S", smokeProjectDir.asFile.absolutePath,
                "-B", smokeBuildDir.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DDAWN_NATIVES_PACKAGE_DIR=${target.stageDirectory.absolutePath}"
            ) + target.configureArguments.filterNot { it.startsWith("-DDAWN_NATIVES_") }
        )
    }
    val smokeTask = tasks.register<Exec>("smoke${target.taskSuffix}") {
        group = "verification"
        description = "Builds the ${target.classifier} dawn-natives link smoke test."
        onlyIf { target.enabledOnHost }
        dependsOn(configureSmokeTask)
        commandLine(
            executableCommand("cmake") + listOf(
                "--build", smokeBuildDir.absolutePath,
                "--config", "Release",
                "--parallel", Runtime.getRuntime().availableProcessors().coerceAtMost(8).coerceAtLeast(1).toString()
            )
        )
    }
    val packageTask = tasks.register<Zip>("package${target.taskSuffix}") {
        group = "distribution"
        description = "Packages ${target.classifier} Dawn static native package."
        onlyIf { target.enabledOnHost }
        dependsOn(smokeTask)
        from(target.stageDirectory)
        archiveFileName.set(target.packageFileName)
        destinationDirectory.set(packagesDir)
    }
    return NativeTaskSet(buildTask, smokeTask, packageTask)
}

val cmakeRoot = layout.buildDirectory.dir("cmake").get().asFile
val stageRoot = layout.buildDirectory.dir("stage").get().asFile
val packagePrefix = "dawn-natives"
val androidPackageClassifier = "android-ndk${nativeConfig.android.ndkVersion.substringBefore('.')}-minsdk${nativeConfig.android.minSdk}"
val androidNdk = androidNdkDirectory()
val androidEnabled = hostOs() == "linux" && androidNdk != null

val linuxTarget = NativeTarget(
    taskSuffix = "LinuxX64",
    classifier = "linux-x64-gcc",
    stageDirectory = File(stageRoot, "linux-x64-gcc"),
    buildDirectory = File(cmakeRoot, "linux-x64-gcc"),
    packageFileName = "$packagePrefix-linux-x64-gcc.zip",
    enabledOnHost = hostOs() == "linux"
)
val windowsTarget = NativeTarget(
    taskSuffix = "WindowsX64",
    classifier = "windows-x64-msvc",
    stageDirectory = File(stageRoot, "windows-x64-msvc"),
    buildDirectory = File(cmakeRoot, "windows-x64-msvc"),
    packageFileName = "$packagePrefix-windows-x64-msvc.zip",
    enabledOnHost = hostOs() == "windows"
)
val macosX64Target = NativeTarget(
    taskSuffix = "MacosX64",
    classifier = "macos-x64-appleclang",
    stageDirectory = File(stageRoot, "macos-x64-appleclang"),
    buildDirectory = File(cmakeRoot, "macos-x64-appleclang"),
    packageFileName = "$packagePrefix-macos-x64-appleclang.zip",
    enabledOnHost = hostOs() == "macos",
    configureArguments = listOf("-DCMAKE_OSX_ARCHITECTURES=x86_64")
)
val macosArm64Target = NativeTarget(
    taskSuffix = "MacosArm64",
    classifier = "macos-arm64-appleclang",
    stageDirectory = File(stageRoot, "macos-arm64-appleclang"),
    buildDirectory = File(cmakeRoot, "macos-arm64-appleclang"),
    packageFileName = "$packagePrefix-macos-arm64-appleclang.zip",
    enabledOnHost = hostOs() == "macos",
    configureArguments = listOf("-DCMAKE_OSX_ARCHITECTURES=arm64")
)

val linuxTasks = registerNativeTarget(linuxTarget)
val windowsTasks = registerNativeTarget(windowsTarget)
val macosX64Tasks = registerNativeTarget(macosX64Target)
val macosArm64Tasks = registerNativeTarget(macosArm64Target)

val androidAbiTaskSets = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").map { abi ->
    val target = NativeTarget(
        taskSuffix = "Android${androidTaskSuffix(abi)}",
        classifier = "$androidPackageClassifier-$abi",
        stageDirectory = File(stageRoot, "$androidPackageClassifier/android/$abi"),
        buildDirectory = File(cmakeRoot, "$androidPackageClassifier-$abi"),
        packageFileName = "$packagePrefix-android-$abi.zip",
        enabledOnHost = androidEnabled,
        configureArguments = listOf(
            "-DCMAKE_TOOLCHAIN_FILE=${androidNdk?.resolve("build/cmake/android.toolchain.cmake")?.absolutePath ?: ""}",
            "-DANDROID_ABI=$abi",
            "-DANDROID_PLATFORM=android-${nativeConfig.android.minSdk}",
            "-DANDROID_STL=c++_static"
        )
    )
    registerNativeTarget(target)
}

val buildAndroidAll = tasks.register("buildAndroidAll") {
    group = "dawn natives"
    description = "Builds and stages all Android ABI Dawn static native packages."
    onlyIf { androidEnabled }
    dependsOn(androidAbiTaskSets.map { it.buildTask })
}

val packageAndroidAll = tasks.register("packageAndroidAll") {
    group = "distribution"
    description = "Packages all Android ABI Dawn static native packages as separate ABI ZIPs."
    onlyIf { androidEnabled }
    dependsOn(androidAbiTaskSets.map { it.packageTask })
}

val packageAll = tasks.register("packageAll") {
    group = "distribution"
    description = "Packages every dawn-natives target supported by the current host/toolchain."
    dependsOn(
        linuxTasks.packageTask,
        windowsTasks.packageTask,
        macosX64Tasks.packageTask,
        macosArm64Tasks.packageTask,
        packageAndroidAll
    )
}

val writeReleaseManifest = tasks.register("writeReleaseManifest") {
    group = "distribution"
    description = "Writes build/packages/dawn-natives-manifest.json for existing package ZIPs."
    inputs.file(configFile)
    inputs.files(fileTree(packagesDir) { include("*.zip") })
    outputs.file(packagesDir.map { it.file("dawn-natives-manifest.json") })
    doLast {
        val packageFiles = packagesDir.get().asFile
            .listFiles { file -> file.isFile && file.extension.equals("zip", ignoreCase = true) }
            ?.sortedBy(File::getName)
            ?: emptyList()
        val packageJson = packageFiles.joinToString(",\n") { file ->
            """
            {
              "name": ${quoteJson(file.name)},
              "sha256": ${quoteJson(sha256(file))},
              "size": ${file.length()}
            }
            """.trimIndent().prependIndent("    ")
        }
        val manifest = """
            {
              "packageVersion": ${quoteJson(nativeConfig.packageConfig.version)},
              "dawn": {
                "repository": ${quoteJson(nativeConfig.dawn.repository)},
                "revision": ${quoteJson(nativeConfig.dawn.revision)}
              },
              "android": {
                "ndkVersion": ${quoteJson(nativeConfig.android.ndkVersion)},
                "minSdk": ${quoteJson(nativeConfig.android.minSdk)},
                "cmakeVersion": ${quoteJson(nativeConfig.android.cmakeVersion)}
              },
              "targets": [
                "dawn_natives::webgpu_dawn"
              ],
              "packages": [
            $packageJson
              ]
            }
        """.trimIndent()
        packagesDir.get().asFile.mkdirs()
        packagesDir.get().file("dawn-natives-manifest.json").asFile.writeText(manifest + System.lineSeparator())
    }
}

val verifyPackages = tasks.register("verifyPackages") {
    group = "verification"
    description = "Builds supported packages and writes the release manifest."
    dependsOn(packageAll, writeReleaseManifest)
}

tasks.register("updateDawn") {
    group = "dawn natives update"
    description = "Updates dawn-natives.toml to a new Dawn commit after verifying it can be fetched."
    val requestedRevision = providers.gradleProperty("dawnNatives.dawnRevision")
    doLast {
        val revision = requestedRevision.orNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw GradleException("Pass -PdawnNatives.dawnRevision=<commit>.")
        val checkDir = layout.buildDirectory.dir("update-check/dawn").get().asFile
        if (!checkDir.resolve(".git").isDirectory) {
            checkDir.parentFile.mkdirs()
            providers.exec {
                commandLine("git", "clone", "--filter=blob:none", "--no-checkout", nativeConfig.dawn.repository, checkDir.absolutePath)
            }.result.get()
        }
        providers.exec {
            workingDir = checkDir
            commandLine("git", "fetch", "--depth", "1", "origin", revision)
        }.result.get()
        replaceTomlValues(mapOf(("dawn" to "revision") to revision))
        writeLockManifest(readNativeConfig())
        logger.lifecycle("Updated Dawn revision to $revision")
    }
}

tasks.register("updateToolchainPin") {
    group = "dawn natives update"
    description = "Updates Android toolchain pins in dawn-natives.toml."
    val androidNdkVersion = providers.gradleProperty("dawnNatives.androidNdkVersion")
    val androidMinSdk = providers.gradleProperty("dawnNatives.androidMinSdk")
    val androidCmakeVersion = providers.gradleProperty("dawnNatives.androidCmakeVersion")
    doLast {
        val replacements = linkedMapOf<Pair<String, String>, String>()
        androidNdkVersion.orNull?.trim()?.takeIf { it.isNotEmpty() }?.let {
            replacements["android" to "ndkVersion"] = it
        }
        androidMinSdk.orNull?.trim()?.takeIf { it.isNotEmpty() }?.let {
            replacements["android" to "minSdk"] = it
        }
        androidCmakeVersion.orNull?.trim()?.takeIf { it.isNotEmpty() }?.let {
            replacements["android" to "cmakeVersion"] = it
        }
        if (replacements.isEmpty()) {
            throw GradleException(
                "Pass at least one of -PdawnNatives.androidNdkVersion=..., " +
                    "-PdawnNatives.androidMinSdk=..., or -PdawnNatives.androidCmakeVersion=..."
            )
        }
        replaceTomlValues(replacements)
        writeLockManifest(readNativeConfig())
        logger.lifecycle("Updated ${replacements.size} toolchain pin(s).")
    }
}

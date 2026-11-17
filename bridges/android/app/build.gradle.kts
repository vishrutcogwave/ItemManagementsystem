import java.io.File
import java.util.Locale
import java.util.Properties
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing material never lives in this repository. Values are read from
// environment variables, or from a properties file outside the repo whose path
// is given by IMS_ANDROID_SIGNING_PROPERTIES. Nothing here throws at
// configuration time: problems are collected and only fail release tasks.
val isWindowsHost = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).startsWith("windows")
val repoCanonical = runCatching { rootDir.canonicalFile.toPath() }.getOrNull()

// true = inside the repository, false = outside, null = could not be resolved
// safely. Unresolvable paths are never treated as outside: they become a
// release-signing problem instead.
val insideRepository: (File) -> Boolean? = { candidate ->
    val target = runCatching { candidate.canonicalFile.toPath() }.getOrNull()
    val repo = repoCanonical
    val targetRoot = target?.root?.toString()
    val repoRoot = repo?.root?.toString()
    if (target == null || repo == null || targetRoot == null || repoRoot == null) {
        null
    } else {
        // The filesystem root must match as well, otherwise a path on another
        // drive whose first directories happen to match would be treated as
        // inside the repository.
        val sameRoot =
            if (isWindowsHost) targetRoot.equals(repoRoot, ignoreCase = true) else targetRoot == repoRoot
        // Canonical paths resolve symlinks and "..", so a link or relative path
        // that lands inside the repository cannot pass as an outside path.
        sameRoot && target.nameCount >= repo.nameCount && (0 until repo.nameCount).all { i ->
            val inside = target.getName(i).toString()
            val base = repo.getName(i).toString()
            if (isWindowsHost) inside.equals(base, ignoreCase = true) else inside == base
        }
    }
}

val externalSigningFile = System.getenv("IMS_ANDROID_SIGNING_PROPERTIES")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { File(it) }

val externalSigning = Properties()
val externalSigningProblem: String? = externalSigningFile?.let { file ->
    when {
        !file.isAbsolute -> "IMS_ANDROID_SIGNING_PROPERTIES must be an absolute path"
        !file.isFile -> "IMS_ANDROID_SIGNING_PROPERTIES does not point to an existing file"
        else -> when (insideRepository(file)) {
            true -> "IMS_ANDROID_SIGNING_PROPERTIES must point outside the repository"
            null -> "IMS_ANDROID_SIGNING_PROPERTIES path could not be resolved safely"
            false -> runCatching {
                // Load into a fresh instance so a partial parse never supplies values.
                val loaded = Properties()
                file.inputStream().use { loaded.load(it) }
                loaded
            }.fold(
                onSuccess = { loaded -> externalSigning.putAll(loaded); null },
                onFailure = { "IMS_ANDROID_SIGNING_PROPERTIES could not be read" },
            )
        }
    }
}

val envValue: (String) -> String? = { name -> System.getenv(name)?.takeIf { it.isNotEmpty() } }
val fileValue: (String) -> String? = { key -> externalSigning.getProperty(key)?.takeIf { it.isNotEmpty() } }

// Passwords are used exactly as supplied: trimming them would silently corrupt
// credentials that legitimately contain leading or trailing whitespace.
val releaseStorePath = (envValue("IMS_ANDROID_KEYSTORE_FILE") ?: fileValue("ims.storeFile"))
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val releaseStorePassword = envValue("IMS_ANDROID_KEYSTORE_PASSWORD") ?: fileValue("ims.storePassword")
val releaseKeyAlias = (envValue("IMS_ANDROID_KEY_ALIAS") ?: fileValue("ims.keyAlias"))
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val releaseKeyPassword = envValue("IMS_ANDROID_KEY_PASSWORD") ?: fileValue("ims.keyPassword")
val releaseStoreType = (
    (envValue("IMS_ANDROID_KEYSTORE_TYPE") ?: fileValue("ims.storeType"))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "PKCS12"
    ).uppercase(Locale.ROOT)

// Only variable names and paths appear in diagnostics; password values are never logged.
val releaseSigningProblem: String? = run {
    val missing = buildList {
        if (releaseStorePath == null) add("IMS_ANDROID_KEYSTORE_FILE")
        if (releaseStorePassword == null) add("IMS_ANDROID_KEYSTORE_PASSWORD")
        if (releaseKeyAlias == null) add("IMS_ANDROID_KEY_ALIAS")
        if (releaseKeyPassword == null) add("IMS_ANDROID_KEY_PASSWORD")
    }
    if (missing.isNotEmpty()) {
        return@run buildString {
            append("missing ")
            append(missing.joinToString(", "))
            if (externalSigningProblem != null) {
                append("; the optional properties file was not used: ")
                append(externalSigningProblem)
            }
        }
    }
    val store = File(releaseStorePath!!)
    when {
        !store.isAbsolute -> "IMS_ANDROID_KEYSTORE_FILE must be an absolute path"
        !store.isFile -> "the configured release keystore does not exist"
        else -> when (insideRepository(store)) {
            true -> "the release keystore must live outside the repository"
            null -> "the release keystore path could not be resolved safely"
            false ->
                if (releaseStoreType == "PKCS12" || releaseStoreType == "JKS") null
                else "IMS_ANDROID_KEYSTORE_TYPE must be PKCS12 or JKS"
        }
    }
}

// Environment variables win over the optional file; when they are complete a
// bad properties path must not fail the build, but it must not stay silent
// either. The reason text never contains credential values.
if (releaseSigningProblem == null && externalSigningProblem != null) {
    logger.warn(
        "Release signing uses environment variables; IMS_ANDROID_SIGNING_PROPERTIES was ignored: " +
            externalSigningProblem,
    )
}

android {
    namespace = "com.itemmanagement.printbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.itemmanagement.printbridge"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningProblem == null) {
                storeFile = File(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeType = releaseStoreType
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Explicit task names only: unit-test and androidTest packaging does not need
// release credentials and must not be blocked by this guard.
val signedReleaseTasks = setOf(
    "assembleRelease",
    "bundleRelease",
    "installRelease",
    "packageRelease",
    "packageReleaseBundle",
    "signReleaseBundle",
    "validateSigningRelease",
)

tasks.matching { it.name in signedReleaseTasks }
    .configureEach {
        val problem = releaseSigningProblem
        if (problem != null) {
            doFirst {
                throw GradleException(
                    "Release signing is not configured: $problem. " +
                        "Set the IMS_ANDROID_* environment variables, or IMS_ANDROID_SIGNING_PROPERTIES " +
                        "pointing to a properties file outside this repository."
                )
            }
        }
    }

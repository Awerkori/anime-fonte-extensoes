import ContentWarning
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.tasks.PackageAndroidArtifact
import keiyoushi.gradle.extensions.DeeplinkFilter
import keiyoushi.gradle.extensions.DeeplinkSpec
import keiyoushi.gradle.extensions.KeiyoushiMultisrcExtension
import keiyoushi.gradle.extensions.VALID_LIB_VERSIONS
import keiyoushi.gradle.extensions.alias
import keiyoushi.gradle.extensions.compileOnly
import keiyoushi.gradle.extensions.implementation
import keiyoushi.gradle.extensions.kei
import keiyoushi.gradle.extensions.libs
import keiyoushi.gradle.extensions.plugins
import keiyoushi.gradle.tasks.GenerateExtensionManifestTask
import keiyoushi.gradle.tasks.GenerateKeepRulesTask
import keiyoushi.gradle.utils.assertWithoutFlag
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

@Suppress("UNUSED")
class PluginExtensionLegacy : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        plugins {
            alias(libs.plugins.android.application)
            alias(libs.plugins.kotlin.serialization)

            alias(kei.plugins.android.base)
            alias(kei.plugins.spotless)
        }

        assertWithoutFlag(!extra.has("pkgNameSuffix")) { "Gradle configuration cannot contain 'pkgNameSuffix'" }
        assertWithoutFlag(extName.all { it.code < 0x180 }) { "Extension name should be romanized" }

        val theme: Project? = if (extra.has("themePkg")) project(":lib-multisrc:$themePkg") else null
        if (theme != null) evaluationDependsOn(theme.path)

        val themeExtension = if (theme != null) {
            theme.extensions.findByType(KeiyoushiMultisrcExtension::class.java)
        } else {
            null
        }

        val themeBaseVersionCode = themeExtension?.baseVersionCode?.orNull
            ?: if (theme?.extra?.has("baseVersionCode") == true) {
                (theme.extra.get("baseVersionCode") as Int)
            } else {
                0
            }

        val themeLibVersion = themeExtension?.libVersion?.orNull
            ?: if (theme?.extra?.has("libVersion") == true) {
                theme.extra.get("libVersion")?.toString()
            } else {
                null
            }

        val effectiveVersionCode = if (theme == null) {
            extVersionCode
        } else {
            themeBaseVersionCode + overrideVersionCode
        }

        val effectiveLibVersion = extraLibVersion
            ?: themeLibVersion
            ?: "14"

        if (themeLibVersion != null) {
            assertWithoutFlag(effectiveLibVersion.toDouble() >= themeLibVersion.toDouble()) {
                "Extension libVersion ($effectiveLibVersion) must not be lower than multisrc ($themePkg) libVersion ($themeLibVersion)."
            }
        }
        assertWithoutFlag(effectiveLibVersion in VALID_LIB_VERSIONS) {
            "libVersion $effectiveLibVersion is not supported. Supported versions: $VALID_LIB_VERSIONS"
        }

        val versionNameValue = "$effectiveLibVersion.$effectiveVersionCode"
        val applicationIdSuffix = "${project.parent?.name}.${project.name}"
        val classNameValue = extClass.removePrefix(".")

        val themeDeeplinks = themeExtension?.deeplinks?.getOrElse(emptyList()) ?: emptyList()
        val defaultHosts = listOfNotNull(extractHost(baseUrl))
        val deeplinkFilters = specsToFilters(themeDeeplinks, defaultHosts)

        val cw = if (extra.has("contentWarning")) {
            extra.get("contentWarning") as ContentWarning
        } else if (isNsfw) {
            ContentWarning.NSFW
        } else {
            ContentWarning.SAFE
        }

        val manifestTask = tasks.register<GenerateExtensionManifestTask>("generateExtensionManifest") {
            this.filters.set(deeplinkFilters)
            this.extensionName.set(extName)
            this.className.set(classNameValue)
            this.contentWarning.set(cw)
            this.extensionLib.set(effectiveLibVersion)
            this.torrent.set(isTorrent)
        }

        android {
            namespace = "eu.kanade.tachiyomi.animeextension"

            defaultConfig {
                this.applicationIdSuffix = applicationIdSuffix
                versionCode = effectiveVersionCode
                versionName = versionNameValue
            }

            sourceSets {
                named("main") {
                    manifest.srcFile(rootProject.file("common/AndroidManifest.xml"))
                    java.directories.clear()
                    java.directories.add("src")
                    kotlin.directories.clear()
                    kotlin.directories.add("src")
                    res.directories.clear()
                    res.directories.add("res")
                    assets.directories.clear()
                    assets.directories.add("assets")
                }
            }

            lint {
                checkReleaseBuilds = false
            }

            signingConfigs {
                create("release") {
                    storeFile = rootProject.file("signingkey.jks")
                    storePassword = providers.environmentVariable("KEY_STORE_PASSWORD").orNull
                    keyAlias = providers.environmentVariable("ALIAS").orNull
                    keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
                }
            }

            buildTypes {
                named("release") {
                    signingConfig = if (rootProject.file("signingkey.jks").exists()) {
                        signingConfigs.getByName("release")
                    } else {
                        signingConfigs.getByName("debug")
                    }
                    isMinifyEnabled = true
                    proguardFiles(rootProject.file("common/proguard-rules.pro"))
                    @Suppress("UnstableApiUsage")
                    vcsInfo.include = false
                }
            }

            dependenciesInfo {
                includeInApk = false
            }

            buildFeatures {
                buildConfig = true
            }

            buildTypes {
                configureEach {
                    buildConfigField("String", "MEGACLOUD_API", "\"https://script.google.com/macros/s/AKfycbxHbYHbrGMXYD2-bC-C43D3njIbU-wGiYQuJL61H4vyy6YVXkybMNNEPJNPPuZrD1gRVA/exec\"")
                    buildConfigField("String", "KISSKH_API", "\"https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id=\"")
                    buildConfigField("String", "KISSKH_SUB_API", "\"https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id=\"")
                    buildConfigField("String", "KAISVA", "\"https://c-kai-8090.amarullz.com\"")
                    buildConfigField("String", "TMDB_API", "\"${System.getenv("TMDB_API")}\"")
                    buildConfigField("String", "WYZIE_API", "\"${System.getenv("WYZIE_API")}\"")
                }
            }

            packaging {
                resources.excludes.add("kotlin-tooling-metadata.json")
            }
        }

        androidComponents {
            onVariants { variant ->
                val variantName = variant.name.replaceFirstChar { it.uppercase() }

                @Suppress("UnstableApiUsage")
                val keepRules = variant.sources.keepRules
                if (keepRules != null) {
                    val task = tasks.register<GenerateKeepRulesTask>("generate${variantName}KeepRules") {
                        this.applicationId.set(variant.applicationId)
                        this.className.set(classNameValue)
                    }
                    keepRules.addGeneratedSourceDirectory(task) { it.outputDir }
                }

                variant.sources.manifests.addStaticManifestFile("AndroidManifest.xml")
                variant.sources.manifests.addGeneratedManifestFile(manifestTask) { it.outputFile }

                variant.outputs.forEach { output ->
                    output.versionCode.set(effectiveVersionCode)
                    output.versionName.set(versionNameValue)
                }
            }
        }

        base {
            archivesName.set("aniyomi-$applicationIdSuffix-v$versionNameValue")
        }

        dependencies {
            if (theme != null) implementation(theme) // Overrides core launcher icons
            implementation(project(":core"))
            compileOnly(libs.bundles.common)
        }

        afterEvaluate {
            tasks.withType<PackageAndroidArtifact>().configureEach {
                createdBy.set("")
                doFirst {
                    appMetadata.asFile.orNull?.writeText("")
                }
            }
        }
    }
}

private fun extractHost(url: String): String? = url.split("://").getOrNull(1)?.split("/")?.first()?.takeIf { it.isNotEmpty() }

private fun specsToFilters(specs: List<DeeplinkSpec>, defaultHosts: List<String>): List<DeeplinkFilter> = specs.mapNotNull { spec ->
    val hosts = spec.hosts.getOrElse(emptyList()).ifEmpty { defaultHosts }
    val paths = spec.pathPatterns.getOrElse(emptyList())
    if (paths.isNotEmpty() && hosts.isNotEmpty()) {
        DeeplinkFilter(hosts, paths)
    } else {
        null
    }
}

private fun Project.android(block: ApplicationExtension.() -> Unit) {
    extensions.configure(block)
}

private fun Project.androidComponents(block: ApplicationAndroidComponentsExtension.() -> Unit) {
    extensions.configure(block)
}

private fun Project.base(block: BasePluginExtension.() -> Unit) {
    extensions.configure(block)
}

private val Project.extName: String
    get() = extra.get("extName") as String

private val Project.extVersionCode: Int
    get() = extra.get("extVersionCode") as Int

private val Project.extraLibVersion: String?
    get() = extra.getOrNull("libVersion")?.toString()

private val Project.extClass: String
    get() = extra.get("extClass") as String

private val Project.isNsfw: Boolean
    get() = extra.getOrNull("isNsfw") == true

private val Project.isTorrent: Boolean
    get() = extra.getOrNull("isTorrent") == true || extra.getOrNull("torrent") == true

private val Project.baseUrl: String
    get() = (extra.getOrNull("baseUrl") as String?).orEmpty()

private val Project.overrideVersionCode: Int
    get() = extra.get("overrideVersionCode") as Int

private val Project.themePkg: String
    get() = extra.get("themePkg") as String

private fun ExtraPropertiesExtension.getOrNull(name: String) = if (has(name)) get(name) else null

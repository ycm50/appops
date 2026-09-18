import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
  `kotlin-dsl`
  alias(libs.plugins.spotless)
  alias(libs.plugins.gradle.versions)
}

dependencies {
  implementation(libs.plugin.android.application)
  implementation(libs.plugin.android.library)
  implementation(libs.plugin.jetbrains.kotlin.android)

  implementation(libs.plugin.spotless)
  implementation(libs.plugin.gradle.versions)

  // deps-download-conventions.gradle.kts 里 apply 的 de.undercouch.download。预编译脚本的
  // plugins {} 块是在 buildSrc 的类路径上解析插件的，所以插件实现必须在这里声明，否则会报
  // “Plugin [id: 'de.undercouch.download'] was not found”。
  implementation(libs.gradle.download.task)

  // Make version catalogs available to convention plugins
  // https://github.com/gradle/gradle/issues/15383
  implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}

spotless {
  java {
    target("src/**/*.java")
    googleJavaFormat(libs.versions.google.java.format.get())
  }

  kotlin {
    target("src/**/*.kt", "src/**/*.kts", "*.kts")
    ktfmt(libs.versions.ktfmt.get())
  }
}

tasks.named("jar").get().dependsOn("spotlessApply")

object Color {
  private const val ESC = 27.toChar()
  const val RED_BOLD = "$ESC[1;91m"
  const val ESC_END = "$ESC[0m"
}

val ignoredVersions = mutableSetOf<String>()

fun isStableVersion(version: String): Boolean {
  return ".*(-|.)(rc|beta|alpha)[0-9]*(|-|.)[0-9.]*$"
      .toRegex()
      .matches(version.lowercase())
      .not()
      .also {
        if (!it && !ignoredVersions.contains(version)) {
          println("${Color.RED_BOLD}Unstable${Color.ESC_END}: $version")
          ignoredVersions.add(version)
        }
      }
}

tasks.withType<DependencyUpdatesTask> {
  rejectVersionIf { !isStableVersion(candidate.version) && isStableVersion(currentVersion) }
}

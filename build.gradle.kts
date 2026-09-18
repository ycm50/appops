import com.diffplug.gradle.spotless.SpotlessExtensionPredeclare

plugins {
  id("spotless-conventions")
  id("deps-download-conventions")
}

allprojects { plugins.apply("dependency-updates-conventions") }

// downloadDeps 预取的插件类路径依赖：AGP / Kotlin / Spotless / gradle-versions 的插件标记
// 及其实现（连传递依赖一起）。坐标从版本目录里取，避免在约定插件里硬编码版本号。
dependencies {
  add("pmxPrefetch", libs.plugin.android.application)
  add("pmxPrefetch", libs.plugin.android.library)
  add("pmxPrefetch", libs.plugin.jetbrains.kotlin.android)
  add("pmxPrefetch", libs.plugin.spotless)
  add("pmxPrefetch", libs.plugin.gradle.versions)
}

spotless { predeclareDeps() }

configure<SpotlessExtensionPredeclare> {
  java { googleJavaFormat(libs.versions.google.java.format.get()) }
  kotlin { ktfmt(libs.versions.ktfmt.get()) }
}

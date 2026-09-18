@Suppress("UnstableApiUsage")

// 与主构建保持一致：预取仓库优先 + 官方原始仓库（不用国内镜像）。
// buildSrc 自己的依赖（AGP / Kotlin / Spotless / de.undercouch.download 等）也走这套仓库，
// 所以 downloadDeps 预取过的构件在这里同样能离线命中。
pluginManagement {
  repositories {
    maven {
      name = "pmxPrefetched"
      url = rootDir.parentFile.resolve("libs/maven-repo").toURI()
    }
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

  repositories {
    mavenLocal()
    maven {
      name = "pmxPrefetched"
      url = rootDir.parentFile.resolve("libs/maven-repo").toURI()
    }
    mavenCentral()
    google()
    gradlePluginPortal()
    maven {
      name = "jitpack"
      url = uri("https://jitpack.io")
    }
  }

  versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

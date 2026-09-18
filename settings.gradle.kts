@Suppress("UnstableApiUsage")

// 插件解析也用同一套仓库：预取仓库优先，然后是原始仓库。没有 pluginManagement 块时默认只有
// gradlePluginPortal()，这里把预取仓库（downloadDeps 的产物）加进去，插件类路径才能离线解析。
pluginManagement {
  repositories {
    maven {
      name = "pmxPrefetched"
      url = rootDir.resolve("libs/maven-repo").toURI()
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

    // 依赖预取仓库（纯 file:// 仓库，--offline 下也能解析）：./gradlew downloadDeps 把依赖
    // 多线程下到这里，见 buildSrc 的 deps-download-conventions.gradle.kts。
    maven {
      name = "pmxPrefetched"
      url = rootDir.resolve("libs/maven-repo").toURI()
    }

    // 只声明官方原始仓库，不用国内镜像。国内网络需要的加速由上面的预取仓库提供；
    // 预取不完整的部分会在这里回退到网络（所以联网构建照常能过）。
    google()
    mavenCentral()
    gradlePluginPortal()
    maven {
      name = "jitpack"
      url = uri("https://jitpack.io") // For libadb-android
    }
  }
}

include(":hidden_apis")

include(":priv_library")

include(":priv_daemon")

include(":app")

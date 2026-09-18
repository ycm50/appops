// 依赖预取。把 PMX 的依赖用 gradle-download-task 插件多线程下载到 libs/maven-repo（一个
// Maven 目录结构的本地仓库），settings.gradle.kts / buildSrc/settings.gradle.kts 把该目录
// 作为第一个仓库 —— 于是不需要国内镜像，依赖命中本地文件，也能配合 --offline 解析。
//
// 用法：
//   ./gradlew downloadDeps     # 解析依赖图 → 写出清单 → 多线程下载（只下缺的文件）
//   ./gradlew cleanDeps        # 清空 libs/maven-repo（怀疑文件被下坏时用）
//
// libs/maven-repo 是本地缓存，不入库（见 .gitignore）。仓库里声明的仓库顺序是
// “本地预取仓库 → 官方原始仓库”，所以就算预取不完整，联网构建也照样能过。
//
// 下载由插件完成（一次提交多个 URL，插件用 Worker API 并行下载，并发度受
// org.gradle.workers.max 控制）；这里只负责算出“还缺哪些文件、分别从哪个原始仓库取”，
// 见 DepsPrefetch.kt。

import de.undercouch.gradle.tasks.download.Download
import java.io.File
import org.gradle.api.file.RelativePath

plugins { id("de.undercouch.download") }

val localRepo: File = rootProject.layout.projectDirectory.dir("libs/maven-repo").asFile
val downloadManifest: File = File(localRepo, "manifest.txt")
val isOfflineBuild: Boolean = gradle.startParameter.isOffline

// 插件类路径（AGP / Kotlin / Spotless / gradle-versions 及其传递依赖）也要预取：它们平时是
// 从 gradlePluginPortal() / google() 拉的，量不小。依赖坐标由根项目 build.gradle.kts 从版本
// 目录里加进来，避免在这里硬编码版本。
configurations.create("pmxPrefetch") {
  isCanBeConsumed = false
  isCanBeResolved = true
  isVisible = false
  description = "downloadDeps 预取的插件类路径依赖（根项目 build.gradle.kts 里添加）"
}

val depsManifest =
    tasks.register("depsManifest") {
      group = "pmx"
      description = "解析依赖图，写出 libs/maven-repo/manifest.txt（待下载文件清单）"

      doLast {
        if (isOfflineBuild) {
          logger.lifecycle("depsManifest：离线模式，跳过清单生成")
          return@doLast
        }
        DepsPrefetch.prefetch(project, localRepo, downloadManifest, logger)
      }
    }

// 清单只列“本地还缺”的文件，所以重复执行时这两个 provider 几乎是空转的。离线模式直接给空
// 列表：插件的构造函数里有个 onlyIf，离线且有文件缺失时会抛异常，空列表能绕开它。
val pendingUrls =
    providers.provider {
      if (isOfflineBuild) {
        emptyList<String>()
      } else {
        DepsPrefetch.readManifest(downloadManifest)
            .filter { entry -> !File(localRepo, entry.relativePath).isFile }
            .map { entry -> entry.url }
      }
    }
val pathByUrl =
    providers.provider {
      DepsPrefetch.readManifest(downloadManifest).associate { entry ->
        entry.url to entry.relativePath
      }
    }

// 待下载文件数：0 或 1 个时走下面的单文件任务（插件的 eachFile 只在多源时可用）。
val pendingCount = providers.provider { pendingUrls.get().size }

tasks.register<Download>("downloadDeps") {
  group = "pmx"
  description = "多线程下载依赖到 libs/maven-repo（原始仓库，不用国内镜像）"

  dependsOn(depsManifest)
  dependsOn("downloadDepsSingleFile")

  // dest 必须是已存在的目录（多源时插件按“URL 末段”取名字，路径由 eachFile 改写成
  // Maven 布局）。
  doFirst { localRepo.mkdirs() }
  dest(localRepo)
  src(pendingUrls)
  // 已经下好的文件不重复下载；先下到临时文件再 move，避免中断留下半个构件 —— 半个文件会
  // 被“已存在”判断当成完整的，之后解析就莫名其妙地失败。
  // 注意 5.7.0 的 API 是 overwrite(boolean) / onlyIfModified(boolean) 这种普通 setter，
  // 不是新版的 Property<Boolean>。
  overwrite(false)
  onlyIfModified(false)
  tempAndMove(true)
  retries(3)
  connectTimeout(30_000)
  readTimeout(120_000)

  // URL → 本地相对路径。目标路径按 Maven 布局拼，Gradle 才能把 libs/maven-repo 当仓库用。
  // 用 RelativePath 逐段构造，避免依赖平台的路径分隔符。eachFile 的 lambda 在 Kotlin DSL 里
  // 是带接收者的（sam-with-receiver），所以直接用 this: DownloadDetails 的成员。
  eachFile {
    pathByUrl.get()[sourceURL.toString()]?.let { relative ->
      setRelativePath(RelativePath(true, *relative.split('/').toTypedArray()))
    }
  }

  onlyIf { !isOfflineBuild && pendingCount.get() > 1 }
}

// 只差一个文件时，上面的 eachFile 用不了（插件要求至少两个源），单独下这一个：
// 单源 + 目标文件不存在时，插件会把文件直接写到 dest 指定的路径上。
tasks.register<Download>("downloadDepsSingleFile") {
  group = "pmx"
  description = "downloadDeps 的单文件补充路径"

  dependsOn(depsManifest)
  src(
      providers.provider {
        DepsPrefetch.readManifest(downloadManifest).firstOrNull()?.url
            ?: downloadManifest.toURI().toString()
      })
  dest(
      providers.provider {
        val entry = DepsPrefetch.readManifest(downloadManifest).firstOrNull()
        File(localRepo, entry?.relativePath ?: downloadManifest.name)
      })
  overwrite(false)
  onlyIfModified(false)
  tempAndMove(true)
  retries(3)
  connectTimeout(30_000)
  readTimeout(120_000)

  onlyIf { !isOfflineBuild && pendingCount.get() == 1 }
}

tasks.register<Delete>("cleanDeps") {
  group = "pmx"
  description = "删除依赖预取缓存 libs/maven-repo"
  delete(localRepo)
}

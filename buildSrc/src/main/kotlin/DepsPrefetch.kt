// 依赖预取（配合 deps-download-conventions.gradle.kts 里的 downloadDeps 任务）。
//
// 目标：不用国内镜像也能快速构建 —— settings.gradle.kts / buildSrc/settings.gradle.kts 只声明
// 官方原始仓库（google() / mavenCentral() / gradlePluginPortal() / jitpack.io），依赖改用
// gradle-download-task 插件多线程下载到 libs/maven-repo 这个本地 Maven 仓库，之后 Gradle
// 直接命中本地文件（纯文件仓库，--offline 下也能解析）。
//
// 这个文件只回答两个问题，真正的下载交给插件：
//   1. 需要哪些构件：把各模块的编译/运行时类路径解析一遍，收集传递依赖的坐标；
//   2. 每个构件从哪个原始仓库取、叫什么文件名：POM 探活（顺序回退到其它原始仓库），
//      读 POM 的 <packaging>、<parent>、<dependencyManagement> import，以及 Gradle Module
//      Metadata 里的 available-at 重定向（androidx 的 KMP 构件会把 jsr 变成 -jvm 模块）。
//
// 结果写到 <localRepo>/manifest.txt，只列出“本地还缺”的文件。所以重复执行很廉价：
// 已经存在的文件连一次探活请求都不会发。

@file:Suppress("UnstableApiUsage")

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.logging.Logger
import org.w3c.dom.Element

/** 一个原始 Maven 仓库（baseUrl 末尾不带斜杠）。 */
internal class PrefetchRepo(val name: String, val baseUrl: String)

/** 一个待预取的模块坐标。pomOnly 表示只要 POM（父 POM / BOM），不要构件本身。 */
internal data class PrefetchModule(
    val group: String,
    val name: String,
    val version: String,
    val pomOnly: Boolean = false
) {
  val key: String = "$group:$name:$version"
  val dir: String = group.replace('.', '/') + "/" + name + "/" + version
  val baseName: String = "$name-$version"
}

/** 本地 Maven 仓库里的一个文件：相对路径（相对本地仓库根）+ 下载地址。 */
internal class PrefetchEntry(val relativePath: String, val url: String)

internal object DepsPrefetch {

  private val GOOGLE: PrefetchRepo =
      PrefetchRepo("google", "https://dl.google.com/dl/android/maven2")
  private val CENTRAL: PrefetchRepo =
      PrefetchRepo("central", "https://repo.maven.apache.org/maven2")
  private val PORTAL: PrefetchRepo = PrefetchRepo("portal", "https://plugins.gradle.org/m2")
  private val JITPACK: PrefetchRepo = PrefetchRepo("jitpack", "https://jitpack.io")

  private val ARTIFACT_EXTENSIONS: List<String> = listOf("jar", "aar")

  private const val USER_AGENT = "pmx-deps-prefetch/1.0"
  private const val CONNECT_TIMEOUT_MS = 15_000
  private const val READ_TIMEOUT_MS = 60_000

  /** 父 POM / BOM 也可能有父 POM，所以按“波”处理，每波之间可能有新模块出现。 */
  private const val MAX_WAVES = 8

  /** 需要解析的配置名。显式列出而不是“所有可解析配置”，是为了避开 kotlinCompilerClasspath / lintChecks 这类动辄上百兆的构建工具类路径。 */
  private val CONFIGURATION_NAMES: List<String> =
      listOf(
          "compileClasspath",
          "compileOnly",
          "compileOnlyApi",
          "runtimeClasspath",
          "annotationProcessor",
          "coreLibraryDesugaring",
          "debugCompileClasspath",
          "debugCompileOnly",
          "debugRuntimeClasspath",
          "releaseCompileClasspath",
          "releaseCompileOnly",
          "releaseRuntimeClasspath",
          "debugAnnotationProcessor",
          "releaseAnnotationProcessor",
          "pmxPrefetch")

  private val AVAILABLE_AT_OBJECT: Regex = Regex("\"available-at\"\\s*:\\s*\\{([^}]*)}")
  private val JSON_FIELD: Regex = Regex("\"(group|module|version)\"\\s*:\\s*\"([^\"]+)\"")

  /** 解析依赖图，把“还缺的文件”写进 manifest，返回待下载文件数。 */
  fun prefetch(root: Project, localRepo: File, manifest: File, logger: Logger): Int {
    val startedAt: Long = System.currentTimeMillis()
    val modules: List<PrefetchModule> = resolveModules(root, logger)
    logger.lifecycle("依赖预取：依赖图里解析出 ${modules.size} 个模块，开始挑选缺失的文件")

    val pool: ExecutorService =
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(4, 16))
    val entries: MutableMap<String, PrefetchEntry> = linkedMapOf()
    val seen: MutableSet<String> = HashSet()
    for (module in modules) {
      seen.add(module.key)
    }

    var wave: List<PrefetchModule> = modules
    var waveIndex = 0
    var unresolved = 0
    try {
      while (wave.isNotEmpty() && waveIndex < MAX_WAVES) {
        waveIndex++
        val tasks: List<Callable<ProbeOutcome>> =
            wave.map { module -> Callable { probeModule(module, localRepo) } }
        val futures = pool.invokeAll(tasks)
        val discovered: MutableList<PrefetchModule> = mutableListOf()
        for (index in wave.indices) {
          val outcome: ProbeOutcome = futures[index].get()
          for (entry in outcome.entries) {
            entries.putIfAbsent(entry.relativePath, entry)
          }
          if (!outcome.resolved) {
            unresolved++
            logger.warn("依赖预取：原始仓库里找不到 ${wave[index].key}，跳过（构建时它会回退到网络）")
          }
          for (module in outcome.discovered) {
            if (seen.add(module.key)) {
              discovered.add(module)
            }
          }
        }
        if (discovered.isNotEmpty()) {
          logger.lifecycle("依赖预取：第 $waveIndex 波又发现 ${discovered.size} 个父 POM / BOM 模块")
        }
        wave = discovered
      }
    } finally {
      pool.shutdown()
    }

    writeManifest(manifest, entries.values)
    val elapsed: Long = System.currentTimeMillis() - startedAt
    logger.lifecycle(
        "依赖预取：清单已写入 ${manifest.absolutePath}（${entries.size} 个文件待下载，" +
            "$unresolved 个模块没找到，用时 ${elapsed / 1000}s）")
    return entries.size
  }

  /** 读取清单（文件不存在时返回空表，离线构建不会因此失败）。 */
  fun readManifest(manifest: File): List<PrefetchEntry> {
    if (!manifest.isFile) {
      return emptyList()
    }
    val entries: MutableList<PrefetchEntry> = mutableListOf()
    for (line in manifest.readLines()) {
      val tab: Int = line.indexOf('\t')
      if (tab <= 0) {
        continue
      }
      val path: String = line.substring(0, tab).trim()
      val url: String = line.substring(tab + 1).trim()
      if (path.isNotEmpty() && url.isNotEmpty()) {
        entries.add(PrefetchEntry(path, url))
      }
    }
    return entries
  }

  private fun writeManifest(manifest: File, entries: Collection<PrefetchEntry>) {
    manifest.parentFile?.mkdirs()
    manifest.writeText(entries.joinToString(separator = "\n") { "${it.relativePath}\t${it.url}" })
  }

  private fun resolveModules(root: Project, logger: Logger): List<PrefetchModule> {
    val found: MutableMap<String, PrefetchModule> = sortedMapOf()
    for (project in root.allprojects) {
      for (name in CONFIGURATION_NAMES) {
        val configuration: Configuration? = project.configurations.findByName(name)
        if (configuration == null || !configuration.isCanBeResolved) {
          continue
        }
        collect(configuration, found, logger)
      }
    }
    return found.values.toList()
  }

  private fun collect(
      configuration: Configuration,
      into: MutableMap<String, PrefetchModule>,
      logger: Logger
  ) {
    val result: ResolutionResult =
        try {
          configuration.incoming.resolutionResult
        } catch (e: Exception) {
          logger.info("依赖预取：配置 ${configuration.name} 解析失败，跳过（${e.message}）")
          return
        }
    for (dependency in result.allDependencies) {
      if (dependency is UnresolvedDependencyResult) {
        logger.warn(
            "依赖预取：${configuration.name} 里有解析不了的依赖 ${dependency.attempted}" +
                "（${dependency.failure.message}）")
      }
    }
    for (component in result.allComponents) {
      val id = component.id
      if (id is ModuleComponentIdentifier) {
        val module = PrefetchModule(id.group, id.module, id.version)
        into.putIfAbsent(module.key, module)
      }
    }
  }

  private fun probeModule(module: PrefetchModule, localRepo: File): ProbeOutcome {
    val dir: File = File(localRepo, module.dir)
    if (isComplete(module, dir)) {
      return ProbeOutcome(emptyList(), emptyList(), true)
    }

    val location: PomLocation =
        findPom(module) ?: return ProbeOutcome(emptyList(), emptyList(), false)
    val repository: PrefetchRepo = location.repository
    val pomFile: String = module.baseName + ".pom"
    val info: PomInfo = parsePom(location.text)

    val entries: MutableList<PrefetchEntry> = mutableListOf()
    entries += wanted(module, dir, repository, listOf(pomFile))

    var availableAt: List<PrefetchModule> = emptyList()
    val moduleFile: String = module.baseName + ".module"
    val localModule: File = File(dir, moduleFile)
    val moduleText: String? =
        if (localModule.isFile) localModule.readText()
        else httpGetText(url(repository, module, moduleFile))
    if (moduleText != null) {
      if (!localModule.isFile) {
        entries.add(PrefetchEntry("${module.dir}/$moduleFile", url(repository, module, moduleFile)))
      }
      availableAt = parseAvailableAt(moduleText, module)
    }

    if (!module.pomOnly && info.packaging != "pom") {
      val extensions: List<String> =
          if (info.packaging == "aar") listOf("aar", "jar") else ARTIFACT_EXTENSIONS
      val local: String? =
          extensions.firstOrNull { ext -> File(dir, module.baseName + "." + ext).isFile }
      if (local == null) {
        // packaging 说 aar 却只有 jar（或反之）的构件是存在的，所以两个后缀都探一次。
        val remote: String? =
            extensions.firstOrNull { ext ->
              exists(url(repository, module, module.baseName + "." + ext))
            }
        if (remote != null) {
          entries += wanted(module, dir, repository, listOf(module.baseName + "." + remote))
        }
      }
    }

    return ProbeOutcome(entries, info.parents + info.imports + availableAt, true)
  }

  private fun isComplete(module: PrefetchModule, dir: File): Boolean {
    if (!File(dir, module.baseName + ".pom").isFile) {
      return false
    }
    if (module.pomOnly) {
      return true
    }
    return ARTIFACT_EXTENSIONS.any { ext -> File(dir, module.baseName + "." + ext).isFile }
  }

  private fun wanted(
      module: PrefetchModule,
      dir: File,
      repository: PrefetchRepo,
      fileNames: List<String>
  ): List<PrefetchEntry> {
    val entries: MutableList<PrefetchEntry> = mutableListOf()
    for (fileName in fileNames) {
      if (!File(dir, fileName).isFile) {
        entries.add(PrefetchEntry("${module.dir}/$fileName", url(repository, module, fileName)))
      }
    }
    return entries
  }

  /** 按坐标猜“最可能”的原始仓库，找不到再回退到其它仓库。 */
  private fun reposFor(module: PrefetchModule): List<PrefetchRepo> {
    val group: String = module.group.lowercase()
    return when {
      group.startsWith("androidx.") ||
          group.startsWith("com.android.") ||
          group.startsWith("com.google.android.") ||
          group.startsWith("com.google.firebase") -> listOf(GOOGLE, CENTRAL, PORTAL, JITPACK)
      group.startsWith("com.github.muntashirakon") -> listOf(JITPACK, CENTRAL, GOOGLE, PORTAL)
      module.name.endsWith(".gradle.plugin") || group.endsWith(".gradle.plugin") ->
          listOf(PORTAL, CENTRAL, GOOGLE, JITPACK)
      else -> listOf(CENTRAL, GOOGLE, PORTAL, JITPACK)
    }
  }

  private fun findPom(module: PrefetchModule): PomLocation? {
    val pomFile: String = module.baseName + ".pom"
    for (repository in reposFor(module)) {
      val text: String? = httpGetText(url(repository, module, pomFile))
      if (text != null) {
        return PomLocation(repository, text)
      }
    }
    return null
  }

  private fun url(repository: PrefetchRepo, module: PrefetchModule, fileName: String): String =
      "${repository.baseUrl}/${module.dir}/$fileName"

  private fun parsePom(text: String): PomInfo {
    val document =
        try {
          val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
          factory.isNamespaceAware = false
          try {
            // 极老的 POM 带外部 DTD 引用；解析失败时下面的 catch 会兜住。
            factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
          } catch (e: Exception) {
            // 解析器不支持这些开关：忽略，继续按默认配置解析。
          }
          factory.newDocumentBuilder().parse(text.byteInputStream(Charsets.UTF_8))
        } catch (e: Exception) {
          return PomInfo("jar", emptyList(), emptyList())
        }

    val root: Element = document.documentElement ?: return PomInfo("jar", emptyList(), emptyList())
    val packaging: String = childText(root, "packaging") ?: "jar"

    val parents: MutableList<PrefetchModule> = mutableListOf()
    val parent: Element? = childElement(root, "parent")
    if (parent != null) {
      coordinate(parent, pomOnly = true)?.let { parents.add(it) }
    }

    val imports: MutableList<PrefetchModule> = mutableListOf()
    val management: Element? = childElement(root, "dependencyManagement")
    val dependencies: Element? = management?.let { childElement(it, "dependencies") }
    if (dependencies != null) {
      val children = dependencies.childNodes
      for (index in 0 until children.length) {
        val node = children.item(index)
        if (node !is Element || node.nodeName != "dependency") {
          continue
        }
        if (childText(node, "scope") == "import") {
          coordinate(node, pomOnly = true)?.let { imports.add(it) }
        }
      }
    }

    return PomInfo(packaging, parents, imports)
  }

  /** Gradle Module Metadata 里的 available-at：构件的真身在另一个模块（如 androidx 的 -jvm）。 */
  private fun parseAvailableAt(text: String, owner: PrefetchModule): List<PrefetchModule> {
    val modules: MutableList<PrefetchModule> = mutableListOf()
    for (match in AVAILABLE_AT_OBJECT.findAll(text)) {
      val body: String = match.groupValues[1]
      val fields: Map<String, String> =
          JSON_FIELD.findAll(body).associate { field ->
            field.groupValues[1] to field.groupValues[2]
          }
      val name: String = fields["module"] ?: continue
      val version: String = fields["version"] ?: continue
      modules.add(PrefetchModule(fields["group"] ?: owner.group, name, version))
    }
    return modules
  }

  private fun coordinate(element: Element, pomOnly: Boolean): PrefetchModule? {
    val group: String = childText(element, "groupId") ?: return null
    val name: String = childText(element, "artifactId") ?: return null
    val version: String = childText(element, "version") ?: return null
    return PrefetchModule(group, name, version, pomOnly)
  }

  private fun childElement(parent: Element, name: String): Element? {
    val children = parent.childNodes
    for (index in 0 until children.length) {
      val node = children.item(index)
      if (node is Element && node.nodeName == name) {
        return node
      }
    }
    return null
  }

  private fun childText(parent: Element, name: String): String? =
      childElement(parent, name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

  private fun httpGetText(url: String): String? {
    val connection: HttpURLConnection =
        try {
          URI.create(url).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
          return null
        }
    connection.instanceFollowRedirects = true
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = READ_TIMEOUT_MS
    connection.setRequestProperty("User-Agent", USER_AGENT)
    return try {
      if (connection.responseCode != HttpURLConnection.HTTP_OK) {
        null
      } else {
        connection.inputStream.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
      }
    } catch (e: Exception) {
      null
    } finally {
      connection.disconnect()
    }
  }

  /** HEAD 探活；某些 CDN 不支持 HEAD，退回 Range 请求。 */
  private fun exists(url: String): Boolean {
    val head: Int? = status(url, "HEAD")
    if (head != null && head in 200..299) {
      return true
    }
    if (head == null || head == 400 || head == 403 || head == 405 || head == 501) {
      val ranged: Int? = status(url, "GET")
      return ranged != null && ranged in 200..299
    }
    return false
  }

  private fun status(url: String, method: String): Int? {
    val connection: HttpURLConnection =
        try {
          URI.create(url).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
          return null
        }
    connection.instanceFollowRedirects = true
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = READ_TIMEOUT_MS
    connection.requestMethod = method
    if (method == "GET") {
      connection.setRequestProperty("Range", "bytes=0-0")
    }
    connection.setRequestProperty("User-Agent", USER_AGENT)
    return try {
      connection.responseCode
    } catch (e: Exception) {
      null
    } finally {
      connection.disconnect()
    }
  }
}

private class ProbeOutcome(
    val entries: List<PrefetchEntry>,
    val discovered: List<PrefetchModule>,
    val resolved: Boolean
)

private class PomLocation(val repository: PrefetchRepo, val text: String)

private class PomInfo(
    val packaging: String,
    val parents: List<PrefetchModule>,
    val imports: List<PrefetchModule>
)

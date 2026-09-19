plugins { id("android-lib-conventions") }

android {
  namespace = "com.mirfatif.privtasks"
  buildTypes { release { consumerProguardFiles("proguard-rules.pro") } }

  buildFeatures { aidl = true }
}

dependencies {
  // Just to resolve APIs in editor
  compileOnly(project(path = ":hidden_apis"))
}

fun createTasksForHiddenAPIs() {
  val dir = File(rootDir, "hidden_apis/build/intermediates/aar_main_jar/")
  // Captured at configuration time: using `project` from a task action is deprecated in Gradle 9
  // and fails with the configuration cache.
  val objectFactory = objects

  for (debug in booleanArrayOf(true, false)) {
    val variant = if (debug) "Debug" else "Release"

    val task = tasks.named("compile" + variant + "JavaWithJavac").get()
    task.dependsOn(":hidden_apis:sync" + variant + "LibJars")

    var hiddenAPIsJarFile = variant.replaceFirstChar { it.lowercaseChar() }
    hiddenAPIsJarFile += "/sync" + variant + "LibJars" + "/classes.jar"

    val hiddenAPIsJar = objectFactory.fileCollection().from(File(dir, hiddenAPIsJarFile))

    task.doFirst {
      this as JavaCompile
      // dependencies.compileOnly() appends the jar but we need to the
      // hidden APIs jar so that to override the Android SDK classes.
      val cp = objectFactory.fileCollection()
      cp.from(hiddenAPIsJar)
      cp.from(classpath)
      classpath = cp
    }
  }
}

afterEvaluate { createTasksForHiddenAPIs() }

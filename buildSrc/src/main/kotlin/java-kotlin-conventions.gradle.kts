import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins { id("spotless-conventions") }

// JDK 21 added the "this-escape" lint, which warns about constructors publishing `this` before the
// object is fully initialized. Data Binding generates such code, and generated sources cannot be
// annotated with @SuppressWarnings, so the whole category is turned off - but only on JDKs which
// know it, so that older compilers never see an unknown lint category.
val javaLintArgs = mutableListOf("-Xlint:all,-serial,-processing")

if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
  javaLintArgs.add("-Xlint:-this-escape")
}

tasks.withType<JavaCompile> { options.compilerArgs.addAll(javaLintArgs) }

tasks.withType<KotlinCompile> {
  compilerOptions.freeCompilerArgs.addAll("-Xjavac-arguments=['-Xlint:all,-serial,-processing']")
}

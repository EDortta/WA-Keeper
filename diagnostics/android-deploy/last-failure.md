# Última falha do android-deploy

- Data UTC: `2026-09-16T17:48:12Z`
- Branch testada: `feature/audio-arbiter-manual-tts`
- Commit testado: `a1e3791e473f631927f7ef2bb33f2ff0045d920a`
- Variante: `release`
- Etapa: `Testes unitários`
- Código de saída: `1`
- Android: `SM-A175F`

```text
==> Atualizando origin/feature/audio-arbiter-manual-tts
==> Android: SM-A175F
==> Branch: feature/audio-arbiter-manual-tts
==> Variante: release
==> Testes unitários
> Task :app:checkKotlinGradlePluginConfigurationErrors
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:dataBindingMergeDependencyArtifactsDebug UP-TO-DATE
> Task :app:generateDebugResValues UP-TO-DATE
> Task :app:generateDebugResources
> Task :app:mergeDebugResources
> Task :app:packageDebugResources
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:mapDebugSourceSetPaths
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:processDebugMainManifest UP-TO-DATE
> Task :app:processDebugManifest UP-TO-DATE
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:parseDebugLocalResources
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:dataBindingGenBaseClassesDebug UP-TO-DATE
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest UP-TO-DATE
> Task :app:processDebugResources
> Task :app:kspDebugKotlin

> Task :app:compileDebugKotlin
e: file://~/Sync/Projects/WA-Keeper/app/src/main/java/br/com/wanotifkeeper/AudioArbiter.kt:324:21 'if' must have both main and 'else' branches if used as an expression

> Task :app:compileDebugKotlin FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:compileDebugKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

BUILD FAILED in 8s
20 actionable tasks: 9 executed, 11 up-to-date

```

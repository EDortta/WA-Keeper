# Última falha do android-deploy

- Data UTC: `2026-09-28T21:08:37Z`
- Branch testada: `development`
- Commit testado: `9a621b34a7494e7e5230496f86dae7926c40491c`
- Variante: `release`
- Etapa: `Testes unitários`
- Código de saída: `1`
- Android: `SM-A175F`

```text
==> Atualizando origin/development
==> Android: SM-A175F
==> Branch: development
==> Variante: release
==> Testes unitários
> Task :app:checkKotlinGradlePluginConfigurationErrors
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:dataBindingMergeDependencyArtifactsDebug UP-TO-DATE
> Task :app:generateDebugResValues UP-TO-DATE
> Task :app:generateDebugResources
> Task :app:packageDebugResources
> Task :app:mergeDebugResources
> Task :app:parseDebugLocalResources
> Task :app:checkDebugAarMetadata
> Task :app:dataBindingGenBaseClassesDebug
> Task :app:mapDebugSourceSetPaths
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:processDebugMainManifest
> Task :app:processDebugManifest
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest UP-TO-DATE
> Task :app:processDebugManifestForPackage
> Task :app:processDebugResources
> Task :app:kspDebugKotlin

> Task :app:compileDebugKotlin
w: file://~/Sync/Projects/WA-Keeper/app/src/main/java/br/com/wanotifkeeper/EntityDetailActivity.kt:298:69 Unnecessary non-null assertion (!!) on a non-null receiver of type String
w: file://~/Sync/Projects/WA-Keeper/app/src/main/java/br/com/wanotifkeeper/EntityDetailActivity.kt:311:75 Unnecessary non-null assertion (!!) on a non-null receiver of type String
w: file://~/Sync/Projects/WA-Keeper/app/src/main/java/br/com/wanotifkeeper/TranscriptionClient.kt:167:31 Unnecessary non-null assertion (!!) on a non-null receiver of type MediaFormat

> Task :app:compileDebugJavaWithJavac
> Task :app:bundleDebugClassesToCompileJar
> Task :app:bundleDebugClassesToRuntimeJar
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:kspDebugUnitTestKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE

> Task :app:testDebugUnitTest

br.com.wanotifkeeper.ContactPhoneTest > internationalPrefixDoubleZeroIsRemoved FAILED
    org.junit.ComparisonFailure at ContactPhoneTest.kt:26

126 tests completed, 1 failed

> Task :app:testDebugUnitTest FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file://~/Sync/Projects/WA-Keeper/app/build/reports/tests/testDebugUnitTest/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 1m 25s
28 actionable tasks: 20 executed, 8 up-to-date

```

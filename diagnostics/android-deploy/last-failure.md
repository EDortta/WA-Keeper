# Última falha do android-deploy

- Data UTC: `2026-09-28T20:35:55Z`
- Branch testada: `development`
- Commit testado: `c17b4c52781a328575cee29b859c91f86a2e1216`
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
> Task :app:mergeDebugResources
> Task :app:packageDebugResources
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:mapDebugSourceSetPaths
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:parseDebugLocalResources
> Task :app:processDebugMainManifest
> Task :app:dataBindingGenBaseClassesDebug
> Task :app:processDebugManifest
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:processDebugManifestForPackage
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest UP-TO-DATE
> Task :app:processDebugResources
> Task :app:kspDebugKotlin

> Task :app:compileDebugKotlin
w: file://~/Sync/Projects/WA-Keeper/app/src/main/java/br/com/wanotifkeeper/ReplySender.kt:113:34 No cast needed
w: file://~/Sync/Projects/WA-Keeper/app/src/main/java/br/com/wanotifkeeper/ReplySender.kt:163:34 No cast needed

> Task :app:compileDebugJavaWithJavac
> Task :app:bundleDebugClassesToRuntimeJar
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:bundleDebugClassesToCompileJar
> Task :app:kspDebugUnitTestKotlin

> Task :app:compileDebugUnitTestKotlin
w: file://~/Sync/Projects/WA-Keeper/app/src/test/java/br/com/wanotifkeeper/ScheduledMessageCoordinatorTest.kt:427:60 The corresponding parameter in the supertype 'ReplySender' is named 'sender'. This may cause problems when calling this function with named arguments.

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

BUILD FAILED in 44s
28 actionable tasks: 19 executed, 9 up-to-date

```

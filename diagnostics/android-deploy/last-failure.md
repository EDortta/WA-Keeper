# Última falha do android-deploy

- Data UTC: `2026-09-16T17:11:29Z`
- Branch testada: `feature/audio-arbiter-manual-tts`
- Commit testado: `98a499d695dd9bd46c34419fa8002e0ab3d946cb`
- Variante: `release`
- Etapa: `Instalação ADB`
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
> Task :app:generateDebugResources UP-TO-DATE
> Task :app:mergeDebugResources UP-TO-DATE
> Task :app:packageDebugResources UP-TO-DATE
> Task :app:parseDebugLocalResources UP-TO-DATE
> Task :app:dataBindingGenBaseClassesDebug UP-TO-DATE
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:mapDebugSourceSetPaths UP-TO-DATE
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:processDebugMainManifest UP-TO-DATE
> Task :app:processDebugManifest UP-TO-DATE
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:kspDebugKotlin UP-TO-DATE
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:bundleDebugClassesToRuntimeJar UP-TO-DATE
> Task :app:bundleDebugClassesToCompileJar UP-TO-DATE
> Task :app:kspDebugUnitTestKotlin UP-TO-DATE
> Task :app:compileDebugUnitTestKotlin UP-TO-DATE
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest UP-TO-DATE
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:testDebugUnitTest UP-TO-DATE

BUILD SUCCESSFUL in 3s
28 actionable tasks: 1 executed, 27 up-to-date
==> Compilando release
> Task :app:buildKotlinToolingMetadata UP-TO-DATE
> Task :app:checkKotlinGradlePluginConfigurationErrors
> Task :app:preBuild UP-TO-DATE
> Task :app:preReleaseBuild UP-TO-DATE
> Task :app:dataBindingMergeDependencyArtifactsRelease UP-TO-DATE
> Task :app:generateReleaseResValues UP-TO-DATE
> Task :app:generateReleaseResources UP-TO-DATE
> Task :app:mergeReleaseResources UP-TO-DATE
> Task :app:packageReleaseResources UP-TO-DATE
> Task :app:parseReleaseLocalResources UP-TO-DATE
> Task :app:dataBindingGenBaseClassesRelease UP-TO-DATE
> Task :app:checkReleaseAarMetadata UP-TO-DATE
> Task :app:mapReleaseSourceSetPaths UP-TO-DATE
> Task :app:createReleaseCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksRelease UP-TO-DATE
> Task :app:processReleaseMainManifest UP-TO-DATE
> Task :app:processReleaseManifest UP-TO-DATE
> Task :app:processReleaseManifestForPackage UP-TO-DATE
> Task :app:processReleaseResources UP-TO-DATE
> Task :app:kspReleaseKotlin UP-TO-DATE
> Task :app:compileReleaseKotlin UP-TO-DATE
> Task :app:javaPreCompileRelease UP-TO-DATE
> Task :app:compileReleaseJavaWithJavac UP-TO-DATE
> Task :app:extractProguardFiles UP-TO-DATE
> Task :app:generateReleaseLintVitalReportModel UP-TO-DATE
> Task :app:lintVitalAnalyzeRelease UP-TO-DATE
> Task :app:lintVitalReportRelease UP-TO-DATE
> Task :app:lintVitalRelease
> Task :app:mergeReleaseJniLibFolders UP-TO-DATE
> Task :app:mergeReleaseNativeLibs NO-SOURCE
> Task :app:stripReleaseDebugSymbols NO-SOURCE
> Task :app:extractReleaseNativeSymbolTables NO-SOURCE
> Task :app:mergeReleaseNativeDebugMetadata NO-SOURCE
> Task :app:checkReleaseDuplicateClasses UP-TO-DATE
> Task :app:dexBuilderRelease UP-TO-DATE
> Task :app:desugarReleaseFileDependencies UP-TO-DATE
> Task :app:mergeExtDexRelease UP-TO-DATE
> Task :app:mergeDexRelease UP-TO-DATE
> Task :app:mergeReleaseArtProfile UP-TO-DATE
> Task :app:mergeReleaseGlobalSynthetics UP-TO-DATE
> Task :app:compileReleaseArtProfile UP-TO-DATE
> Task :app:mergeReleaseShaders UP-TO-DATE
> Task :app:compileReleaseShaders NO-SOURCE
> Task :app:generateReleaseAssets UP-TO-DATE
> Task :app:mergeReleaseAssets UP-TO-DATE
> Task :app:compressReleaseAssets UP-TO-DATE
> Task :app:extractReleaseVersionControlInfo
> Task :app:processReleaseJavaRes UP-TO-DATE
> Task :app:mergeReleaseJavaResource UP-TO-DATE
> Task :app:optimizeReleaseResources UP-TO-DATE
> Task :app:collectReleaseDependencies UP-TO-DATE
> Task :app:sdkReleaseDependencyData UP-TO-DATE
> Task :app:validateSigningRelease UP-TO-DATE
> Task :app:writeReleaseAppMetadata UP-TO-DATE
> Task :app:writeReleaseSigningConfigVersions UP-TO-DATE
> Task :app:packageRelease UP-TO-DATE
> Task :app:createReleaseApkListingFileRedirect UP-TO-DATE
> Task :app:assembleRelease

BUILD SUCCESSFUL in 1s
49 actionable tasks: 3 executed, 46 up-to-date
==> Instalando sem apagar dados
Performing Streamed Install
adb: failed to install app/build/outputs/apk/release/app-release.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package br.com.wanotifkeeper signatures do not match newer version; ignoring!]


A instalação falhou. NÃO desinstale o WA-Keeper para "resolver" assinatura incompatível:
a desinstalação apagaria o banco local. Corrija a assinatura/keystore e rode novamente.

```

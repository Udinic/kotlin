/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.constistency.structure

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.LightProjectDescriptor
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime
import org.jetbrains.kotlin.idea.caches.project.getModuleInfo
import org.jetbrains.kotlin.idea.fir.low.level.api.api.DiagnosticCheckerFilter
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getFirFile
import org.jetbrains.kotlin.idea.fir.low.level.api.createResolveStateForNoCaching
import org.jetbrains.kotlin.idea.test.*
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.ConfigurationKind
import org.jetbrains.kotlin.test.TestConfiguration
import org.jetbrains.kotlin.test.TestRunner
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.builders.testConfiguration
import org.jetbrains.kotlin.test.builders.testRunner
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.frontend.fir.FirOutputArtifact
import org.jetbrains.kotlin.test.model.*
import org.jetbrains.kotlin.test.runners.*
import org.jetbrains.kotlin.test.services.*
import org.jetbrains.kotlin.test.services.configuration.JvmEnvironmentConfigurator
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance

abstract class AbstractCompilerBasedTest : KotlinLightCodeInsightFixtureTestCase() {
    private var _configuration: TestConfiguration? = null
    private var _modules: TestModuleStructure? = null

    protected val modules: TestModuleStructure get() = _modules!!
    protected val configuration: TestConfiguration get() = _configuration!!

    inner class LowLevelFirFrontendFacade(
        testServices: TestServices
    ) : FrontendFacade<FirOutputArtifact>(testServices, FrontendKinds.FIR) {
        override val additionalDirectives: List<DirectivesContainer>
            get() = listOf(FirDiagnosticsDirectives)

        override fun analyze(module: TestModule): LowLevelFirOutputArtifact {
            val files = module.files.associateWith { file ->
                val text = testServices.sourceFileProvider.getContentOfSourceFile(file)
                myFixture.addFileToProject(file.relativePath, text)
            }
            val ktFile = files.values.firstIsInstance<KtFile>()
            val moduleInfo = ktFile.getModuleInfo()
            val resolveState = createResolveStateForNoCaching(moduleInfo)

            val allFirFiles = files.mapNotNull { (testFile, psiFile) ->
                if (psiFile !is KtFile) return@mapNotNull null
                testFile to psiFile.getFirFile(resolveState)
            }.toMap()

            val diagnosticCheckerFilter = if (FirDiagnosticsDirectives.WITH_EXTENDED_CHECKERS in module.directives) {
                DiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS
            } else DiagnosticCheckerFilter.ONLY_COMMON_CHECKERS

            val analyzerFacade = LowLevelFirAnalyzerFacade(resolveState, allFirFiles, diagnosticCheckerFilter)
            return LowLevelFirOutputArtifact(resolveState.rootModuleSession, analyzerFacade)
        }
    }

    override fun isFirPlugin(): Boolean = true


    override fun setUp() {
        if (!isAllFilesPresentInTest()) {
            setupConfiguration()
        }
        super.setUp()
    }

    private fun setupConfiguration() {
        _configuration = testConfiguration(testPath()) {
            testInfo = KotlinTestInfo(
                className = "_undefined_",
                methodName = "_testUndefined_",
                tags = emptySet()
            )
            configureTest()
            AbstractKotlinCompilerTest.defaultConfiguration(this)
            unregisterAllFacades()
            useFrontendFacades(::LowLevelFirFrontendFacade)
            assertions = JUnit4AssertionsService
        }
        _modules = configuration.moduleStructureExtractor.splitTestDataByModules(testPath(), configuration.directives)
    }

    override fun tearDown() {
        _configuration = null
        _modules = null
        super.tearDown()
    }

    abstract fun TestConfigurationBuilder.configureTest()


    fun doTest(path: String) {
        if (ignoreTest()) return
        TestRunner(configuration).runTest(path)
    }

    private fun ignoreTest(): Boolean {
        if (modules.modules.size > 1) {
            return true // multimodule tests are not supported
        }

        val singleModule = modules.modules.single()
        if (singleModule.files.none { it.isKtFile }) {
            return true // nothing to highlight
        }

        return false
    }

    override fun getProjectDescriptor(): LightProjectDescriptor {
        if (isAllFilesPresentInTest()) return KotlinLightProjectDescriptor.INSTANCE

        val allDirectives = modules.allDirectives

        val configurationKind = JvmEnvironmentConfigurator.extractConfigurationKind(allDirectives)
        val jdkKind = JvmEnvironmentConfigurator.extractJdkKind(allDirectives)

        fun stdLib() =
            if (JvmEnvironmentConfigurationDirectives.STDLIB_JDK8 in allDirectives) ForTestCompileRuntime.runtimeJarForTestsWithJdk8()
            else ForTestCompileRuntime.runtimeJarForTests()

        val libraryFiles = when (configurationKind) {
            ConfigurationKind.JDK_NO_RUNTIME -> emptyList()
            ConfigurationKind.JDK_ONLY -> listOf(ForTestCompileRuntime.minimalRuntimeJarForTests())
            ConfigurationKind.NO_KOTLIN_REFLECT -> listOf(stdLib())
            ConfigurationKind.ALL -> listOf(stdLib(), ForTestCompileRuntime.reflectJarForTests())
        }

        return object : KotlinJdkAndLibraryProjectDescriptor(libraryFiles) {
            override fun getSdk(): Sdk = PluginTestCaseBase.jdk(jdkKind)
        }
    }
}
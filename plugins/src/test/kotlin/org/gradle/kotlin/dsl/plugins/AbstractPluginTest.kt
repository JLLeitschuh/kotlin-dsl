package org.gradle.kotlin.dsl.plugins

import org.gradle.kotlin.dsl.fixtures.AbstractIntegrationTest
import org.gradle.kotlin.dsl.fixtures.gradleRunnerFor

import org.gradle.util.TextUtil.normaliseFileSeparators

import org.junit.Before

import java.io.File
import java.util.*

open class AbstractPluginTest : AbstractIntegrationTest() {

    @Before
    fun setUpTestPluginRepository() {
        val testRepository = normaliseFileSeparators(absolutePathOf("build/repository"))
        val futureVersion = loadTestProperties()["version"]
        withFile("settings.gradle", """
            pluginManagement {
                repositories {
                    maven { url = file("$testRepository") }
                    maven { url = "https://jcenter.bintray.com/" }
                    maven { url = "https://dl.bintray.com/kotlin/kotlin-dev/" }
                }
                resolutionStrategy {
                    eachPlugin {
                        if (requested.id.namespace == "org.gradle.kotlin") {
                            useVersion("$futureVersion")
                        }
                    }
                }
            }
        """)
    }

    private
    fun loadTestProperties(): Properties =
        javaClass.getResourceAsStream("/test.properties").use {
            Properties().apply { load(it) }
        }

    protected
    fun buildWithPlugin(vararg arguments: String) =
        gradleRunnerFor(projectRoot)
            .withArguments("--stacktrace", *arguments)
            .build()!!

    private
    fun absolutePathOf(path: String) =
        File(path).absolutePath
}

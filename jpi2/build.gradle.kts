plugins {
    `java-library`
    signing
    alias(libs.plugins.plugin.publish)
    `java-gradle-plugin`
    `kotlin-dsl`
}

description = "V2 Gradle plugin for building Jenkins plugins with Gradle 8+"

dependencies {
    implementation(gradleApi())
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.accessmodifier.checker)
    compileOnly(libs.localizer.maven)
    compileOnly(libs.maven.plugin.api)

    // Test dependencies
    testImplementation(testFixtures(project(":core")))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.api)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
    testImplementation(libs.commons.io)
    testImplementation(libs.maven.model)
    testCompileOnly(libs.develocity.testing.annotations)
    testCompileOnly(libs.jetbrains.annotations)
    testRuntimeOnly(libs.junit5.jupiter)
    testImplementation(libs.junit5.launcher)
    testImplementation(libs.jackson.databind)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

publishing {
    publications {
        create<MavenPublication>("pluginMaven") {
            pom {
                name.set("Gradle JPI Plugin V2")
                description.set("V2 plugin for building Jenkins plugins with Gradle 8+")
                url.set("http://github.com/jenkinsci/gradle-jpi-plugin")
                scm {
                    url.set("https://github.com/jenkinsci/gradle-jpi-plugin")
                }
                licenses {
                    license {
                        name.set("Apache 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("abayer")
                        name.set("Andrew Bayer")
                    }
                    developer {
                        id.set("kohsuke")
                        name.set("Kohsuke Kawaguchi")
                    }
                    developer {
                        id.set("daspilker")
                        name.set("Daniel Spilker")
                    }
                    developer {
                        id.set("sghill")
                        name.set("Steve Hill")
                    }
                }
            }
        }
    }
}

signing {
    val signingKeyId: String? by project
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
    setRequired { setOf("jenkins.username", "jenkins.password").all { project.hasProperty(it) } }
}

gradlePlugin {
    plugins {
        create("pluginV2") {
            id = "org.jenkins-ci.jpi2"
            implementationClass = "org.jenkinsci.gradle.plugins.jpi2.V2JpiPlugin"
            displayName = "A plugin for building Jenkins plugins"
            website.set("https://github.com/jenkinsci/gradle-jpi-plugin")
            vcsUrl.set("https://github.com/jenkinsci/gradle-jpi-plugin")
            description = "A plugin for building Jenkins plugins with Gradle 8+"
            tags.set(listOf("jenkins"))
        }
    }
}

fun Project.stringProp(named: String): String? = findProperty(named) as String?

tasks.addRule("Pattern: testGradle<ID>") {
    val taskName = this
    if (!taskName.startsWith("testGradle")) return@addRule
    val task = tasks.register(taskName)
    for (javaVersion in listOf(17)) {
        val javaSpecificTask = tasks.register<Test>("${taskName}onJava${javaVersion}") {
            val gradleVersion = taskName.substringAfter("testGradle")
            systemProperty("gradle.under.test", gradleVersion)
            setTestNameIncludePatterns(listOf("*IntegrationTest"))
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(javaVersion))
            })
        }
        task.configure {
            dependsOn(javaSpecificTask)
        }
    }
}

val checkPhase = tasks.named("check")
val publishToGradle = tasks.named("publishPlugins")
publishToGradle.configure {
    dependsOn(checkPhase)
}

rootProject.tasks.named("postRelease").configure {
    dependsOn(publishToGradle)
}

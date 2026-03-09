import java.nio.file.Files
import java.util.concurrent.Callable

plugins {
    `java-library`
    `maven-publish`
    id("uk.jamierocks.propatcher") version "2.0.1"
    id("org.cadixdev.licenser") version "0.5.0"
    id("com.google.cloud.artifactregistry.gradle-plugin") version "2.2.0"
}

val artifactId = name.lowercase()
base.archivesName = artifactId

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
}

configurations {
    register("jdt") {
        isTransitive = false
    }
}

repositories {
    mavenCentral()
}

val jdt = "org.eclipse.jdt:org.eclipse.jdt.core:3.25.0"
dependencies {
    api(jdt)

    // from https://github.com/FabricMC/Mercury/commit/473a1f988c835628a2efe64f3ccfe0079e889bdd#diff-c0dfa6bc7a8685217f70a860145fbdf416d449eaff052fa28352c5cec1a98c06R38-R56
    // JDT pulls all of these deps in, however they do not specify the exact version to use so they can get updated without us knowing.
    // Depend specifically on these versions to prevent them from being updated under our feet.
    // The POM is also patched later on to as this strict versioning does not make it through.
    implementation("org.eclipse.platform:org.eclipse.compare.core:[3.6.1000]")
    implementation("org.eclipse.platform:org.eclipse.core.commands:[3.9.800]")
    implementation("org.eclipse.platform:org.eclipse.core.contenttype:[3.7.900]")
    implementation("org.eclipse.platform:org.eclipse.core.expressions:[3.7.100]")
    implementation("org.eclipse.platform:org.eclipse.core.filesystem:[1.7.700]")
    implementation("org.eclipse.platform:org.eclipse.core.jobs:[3.10.1100]")
    implementation("org.eclipse.platform:org.eclipse.core.resources:[3.14.0]")
    implementation("org.eclipse.platform:org.eclipse.core.runtime:[3.20.100]")
    implementation("org.eclipse.platform:org.eclipse.equinox.app:[1.5.100]")
    implementation("org.eclipse.platform:org.eclipse.equinox.common:[3.14.100]")
    implementation("org.eclipse.platform:org.eclipse.equinox.preferences:[3.8.200]")
    implementation("org.eclipse.platform:org.eclipse.equinox.registry:[3.10.100]")
    implementation("org.eclipse.platform:org.eclipse.osgi:[3.16.200]")
    implementation("org.eclipse.platform:org.eclipse.team.core:[3.8.1100]")
    implementation("org.eclipse.platform:org.eclipse.text:[3.11.0]")

    // TODO: Split in separate modules
    api("org.cadixdev:at:0.1.0-rc1")
    api("org.cadixdev:lorenz:0.5.7")

    "jdt"("$jdt:sources")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.cadixdev:lorenz-io-jam:0.5.7")
}

tasks.withType<Javadoc> {
    exclude("${project.group}.$artifactId.jdt.".replace('.', '/'))
}

// Patched ImportRewrite from JDT
patches {
    patches = file("patches")
    rootDir = file("build/jdt/original")
    target = file("build/jdt/patched")
}
val jdtSrcDir = file("jdt")
Files.createDirectories(patches.target.toPath())

val extract = task<Copy>("extractJdt") {
    dependsOn(configurations["jdt"])
    from(Callable { zipTree(configurations["jdt"].singleFile) })
    destinationDir = patches.rootDir

    include("org/eclipse/jdt/core/dom/rewrite/ImportRewrite.java")
    include("org/eclipse/jdt/internal/core/dom/rewrite/imports/*.java")
}
tasks["applyPatches"].inputs.files(extract)
tasks["resetSources"].dependsOn(extract)

val renames = listOf(
        "org.eclipse.jdt.core.dom.rewrite" to "$group.$artifactId.jdt.rewrite.imports",
        "org.eclipse.jdt.internal.core.dom.rewrite.imports" to "$group.$artifactId.jdt.internal.rewrite.imports"
)

fun createRenameTask(prefix: String, inputDir: File, outputDir: File, renames: List<Pair<String, String>>): Task
        = task<Copy>("${prefix}renameJdt") {
    destinationDir = file(outputDir)

    renames.forEach { (old, new) ->
        from("$inputDir/${old.replace('.', '/')}") {
            into("${new.replace('.', '/')}/")
        }
    }

    filter { renames.fold(it) { s, (from, to) -> s.replace(from, to) } }
}

val renameTask = createRenameTask("", patches.target, jdtSrcDir, renames)
renameTask.inputs.files(tasks["applyPatches"])

tasks["makePatches"].inputs.files(createRenameTask("un", jdtSrcDir, patches.target, renames.map { (a,b) -> b to a }))
sourceSets["main"].java.srcDirs(renameTask)

tasks.jar.configure {
    manifest.attributes(mapOf("Automatic-Module-Name" to "${project.group}.$artifactId"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}

val sourceJar = task<Jar>("sourceJar") {
    archiveClassifier = "sources"
    from(sourceSets["main"].allSource)
}

val javadocJar = task<Jar>("javadocJar") {
    archiveClassifier = "javadoc"
    from(tasks["javadoc"])
}

artifacts {
    add("archives", sourceJar)
    add("archives", javadocJar)
}

license {
    header = file("HEADER")
    exclude("$group.$artifactId.jdt.".replace('.', '/'))
}

val isSnapshot = version.toString().endsWith("-SNAPSHOT")

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = base.archivesBaseName

            artifact(sourceJar)
            artifact(javadocJar)

            pom {
                val name: String by project
                val description: String by project
                val url: String by project
                name(name)
                description(description)
                url(url)

                scm {
                    url(url)
                    connection("scm:git:$url.git")
                    developerConnection.set(connection)
                }

                issueManagement {
                    system("GitHub Issues")
                    url("$url/issues")
                }

                licenses {
                    license {
                        name("Eclipse Public License, Version 2.0")
                        url("https://www.eclipse.org/legal/epl-2.0/")
                        distribution("repo")
                    }
                }

                developers {
                    developer {
                        id("jamierocks")
                        name("Jamie Mansfield")
                        email("jmansfield@cadixdev.org")
                        url("https://www.jamiemansfield.me/")
                        timezone("Europe/London")
                    }
                }

                withXml {
                    (((asNode().get("dependencies") as groovy.util.NodeList).first() as groovy.util.Node).value() as groovy.util.NodeList)
                        .removeIf { node ->
                            val group = ((((node as groovy.util.Node).get("groupId") as groovy.util.NodeList).first() as groovy.util.Node).value() as groovy.util.NodeList).first() as String;
                            group.startsWith("org.eclipse.")
                        }
                }
            }
        }
    }

    repositories {
        maven {
            url = uri("artifactregistry://us-central1-maven.pkg.dev/mw-lunarclient-maven-repo/public")
        }
    }
}

operator fun Property<String>.invoke(v: String) = set(v)

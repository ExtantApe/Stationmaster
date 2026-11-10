plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.extantape"
version = "0.4.1"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

val paperApi = "io.papermc.paper:paper-api:26.1.2.build.74-stable"
val luckPermsApi = "net.luckperms:api:5.4"

dependencies {
    compileOnly(paperApi)
    compileOnly(luckPermsApi)
    implementation("com.github.ExtantApe:Signalbox:v0.3.0")

    testImplementation(paperApi)
    testImplementation(luckPermsApi)
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
}

// Signalbox goes inside the jar, relocated, so it can never clash with another plugin's copy. The shaded jar is the
// only one built, so the release workflow's Usher-*.jar finds exactly one.
tasks.shadowJar {
    archiveBaseName.set("Usher")
    archiveClassifier.set("")
    from("LICENSE")
    relocate("com.extantape.signalbox", "com.extantape.usher.libs.signalbox")
}
tasks.jar { enabled = false }
tasks.assemble { dependsOn(tasks.shadowJar) }

// Test only: a tiny plugin that logs Usher's events, for the bot tests in test/. Never shipped.
val spy: SourceSet = sourceSets.create("spy") {
    java.srcDir("test/spy/java")
    resources.srcDir("test/spy/resources")
    compileClasspath += sourceSets.main.get().output
}
dependencies { "spyCompileOnly"(paperApi) }
tasks.register<Jar>("spyJar") {
    from(spy.output)
    archiveFileName.set("UsherSpy.jar")
    destinationDirectory.set(layout.buildDirectory.dir("test-spy"))
}

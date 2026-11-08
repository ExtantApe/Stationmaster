plugins {
    java
}

group = "com.extantape"
version = "0.1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val paperApi = "io.papermc.paper:paper-api:26.1.2.build.74-stable"
val luckPermsApi = "net.luckperms:api:5.4"

dependencies {
    compileOnly(paperApi)
    compileOnly(luckPermsApi)

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

tasks.jar {
    archiveBaseName.set("Stationmaster")
    from("LICENSE")
}

// Test only: a tiny plugin that logs Stationmaster's events, for the bot tests in test/. Never shipped.
val spy: SourceSet = sourceSets.create("spy") {
    java.srcDir("test/spy/java")
    resources.srcDir("test/spy/resources")
    compileClasspath += sourceSets.main.get().output
}
dependencies { "spyCompileOnly"(paperApi) }
tasks.register<Jar>("spyJar") {
    from(spy.output)
    archiveFileName.set("StationmasterSpy.jar")
    destinationDirectory.set(layout.buildDirectory.dir("test-spy"))
}

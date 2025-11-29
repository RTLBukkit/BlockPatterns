import xyz.jpenilla.resourcefactory.bukkit.BukkitPluginYaml
import xyz.jpenilla.runpaper.task.RunServer

plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-SNAPSHOT" // use snapshots because of minecraft deobf causing big changes
    id("xyz.jpenilla.run-paper") version "3.0.2" //Run Debug Server
    id("xyz.jpenilla.resource-factory-bukkit-convention") version "1.3.0" // Generates plugin.yml based on the Gradle config
    id("com.gradleup.shadow") version "9.2.2"
}

group = "au.id.rleach"
version = "0.1.0"
description = "Block Pattern Detection Library"

java {
    toolchain {
        vendor = JvmVendorSpec.JETBRAINS
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.lucko.me/")
}


val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
    // Paper userdev (experimental). This provides the dev bundle for compilation & remapping.
    paperweightDevelopmentBundle("io.papermc.paper:dev-bundle:1.21.10-R0.1-SNAPSHOT")

    // Developer happiness deps
    compileOnly("net.kyori:adventure-api:4.17.0") // provided by Paper at runtime
    implementation("net.kyori:adventure-platform-bukkit:4.3.3") // shade
    implementation("net.kyori:adventure-text-minimessage:4.17.0") // shade
    implementation("org.incendo:cloud-paper:2.0.0-beta.10") // shade
    implementation("me.lucko:helper:5.6.13") // shade
    implementation("dev.dejvokep:boosted-yaml:1.3.5") // shade

    // --- Testing ---
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.+")
    mockitoAgent("org.mockito:mockito-core:5.+") { isTransitive = false }
    // Include Paper dev-bundle on the test classpath so we can compile & run unit tests that use NMS classes
    // Note: This brings in deobfuscated server classes suitable for unit testing simple value types (e.g., BlockPos, Direction)
    testImplementation("io.papermc.paper:dev-bundle:1.21.10-R0.1-SNAPSHOT")
}

tasks.processResources {
    // Expand version into plugin.yml if needed later
    filteringCharset = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.test {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
    jvmArgs.add("-javaagent:${mockitoAgent.asPath}")
}

tasks.jar {
    // Produce the development jar (unmapped). For running on a real server, use the reobfJar below.
    archiveBaseName.set("block-patterns")
}

// Shadow (shade) third-party libs into the plugin jar and then reobfuscate for distribution
tasks.shadowJar {
    fun reloc(pkg: String) = relocate(pkg, "au.id.rleach.blockpatterns.libs.$pkg")

    archiveClassifier.set("all")
    // Relocate to avoid classpath conflicts on servers
    reloc("org.incendo.cloud")
    reloc("dev.dejvokep.boostedyaml")
    reloc("me.lucko.helper")
    reloc("net.kyori.adventure.text.minimessage")
    reloc("net.kyori.adventure.platform.bukkit")

    mergeServiceFiles()
    // Needed for mergeServiceFiles to work properly in Shadow 9+
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

tasks.runServer {
    minecraftVersion("1.21.10")
}

tasks.withType(xyz.jpenilla.runtask.task.AbstractRun::class) {
    javaLauncher = javaToolchains.launcherFor {
        vendor = JvmVendorSpec.JETBRAINS
        languageVersion = JavaLanguageVersion.of(25)
    }
    jvmArgs("-XX:+AllowEnhancedClassRedefinition")
}
tasks.withType<RunServer>().configureEach {
    downloadPlugins {
        modrinth("lanbroadcaster", "toiZxpnA")
    }
    jvmArgs(
        // Friendlier GC/logging and faster startup in dev
        "-Dpaper.disable-telemetry=true",
        "-Dcom.mojang.eula.agree=true",
        "-XX:+UseG1GC",
        "-XX:+UseStringDeduplication",
        // Helpful for class redefinition by IntelliJ hotswap
        "-Dreloaded.classes.hotswap=true"
    )

    // Pass any extra program args here (e.g., --nogui)
    args("--nogui")
}


// Configure plugin.yml generation
// - name, version, and description are inherited from the Gradle project.
bukkitPluginYaml {
    main = "au.id.rleach.blockpatterns.BlockPatternsPlugin"
    load = BukkitPluginYaml.PluginLoadOrder.STARTUP
    authors.add("ryan_the_leach")
    apiVersion = "1.21.10"
}

// Notes for developers (kept as comments):
// - Use IntelliJ: Run -> Attach Debugger to remote JVM (when starting with debugger) to enable method body hotswap.
// - For true structural hot-swap beyond method bodies, consider HotswapAgent + DCEVM; JDK 25 support may be experimental.

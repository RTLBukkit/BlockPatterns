Project: BlockPatterns — Advanced Development Guidelines

Scope: This document captures project-specific knowledge needed to build, run, and work effectively on this repository. It intentionally omits generic Java/Paper guidance.

1. Toolchains, Build System, and Important Plugins
- Gradle Versioning/Plugins
  - Uses Paperweight userdev: `io.papermc.paperweight.userdev` (2.0.0-SNAPSHOT). Dev bundle: `io.papermc.paper:dev-bundle:1.21.10-R0.1-SNAPSHOT`.
  - Run/dev server via `xyz.jpenilla.run-paper` (3.0.2).
  - Plugin.yml generation via `xyz.jpenilla.resource-factory-bukkit-convention` (1.3.0).
  - Shading via `com.gradleup.shadow` (9.2.2).
  - Settings plugin: `org.gradle.toolchains.foojay-resolver-convention` in `settings.gradle.kts` to auto-provision JDKs.

- Java Toolchain
  - Java 25 (vendor JetBrains) is enforced in `build.gradle.kts` both for compilation and run tasks.
    - Implication: local builds do not require a preinstalled JDK if the Foojay resolver is active; Gradle can provision JDK 25 automatically.
    - If you intentionally override the toolchain, you must ensure consistent bytecode level and that Paper dev bundle compatibility is preserved.

- Encoding/Compilation
  - UTF-8 throughout (`JavaCompile`, `Javadoc`).
  - `options.release = 25` (produces Java 25 bytecode).

2. Building Artifacts and What Each Is For
- Dev jar (unmapped):
  - `./gradlew jar` produces `build/libs/block-patterns-<version>.jar`. This is typically for IDE-attached dev flows; it is not reobfuscated for distribution.

- Shaded jar:
  - `./gradlew shadowJar` produces `build/libs/block-patterns-<version>-all.jar` with shaded dependencies.
  - Package relocations applied to avoid classpath conflicts at runtime:
    - `org.incendo.cloud` → `au.id.rleach.blockpatterns.libs.org.incendo.cloud`
    - `dev.dejvokep.boostedyaml` → `au.id.rleach.blockpatterns.libs.dev.dejvokep.boostedyaml`
    - `me.lucko.helper` → `au.id.rleach.blockpatterns.libs.me.lucko.helper`
    - `net.kyori.adventure.text.minimessage` → `au.id.rleach.blockpatterns.libs.net.kyori.adventure.text.minimessage`
    - `net.kyori.adventure.platform.bukkit` → `au.id.rleach.blockpatterns.libs.net.kyori.adventure.platform.bukkit`
  - Service files are merged; duplicates allowed under `META-INF/services/**` to satisfy Shadow 9+ behavior.

- Distribution (reobfuscated) jar:
  - Paperweight provides `reobfJar` for remapping to runtime (obfuscated) names: `./gradlew reobfJar`.
  - Typical release flow if you need a shaded, runtime-ready jar:
    1) `./gradlew shadowJar`
    2) `./gradlew reobfJar`
  - Keep the Minecraft version alignment (1.21.10 dev bundle) consistent with the server you target.

3. Running the Development Server
- Primary task: `./gradlew runDevBundleServer` (provided by `xyz.jpenilla.run-paper`).
  - Server version is pinned via `minecraftVersion("1.21.10")`.
  - JVM args configured for dev ergonomics:
    - `-Dpaper.disable-telemetry=true`, `-Dcom.mojang.eula.agree=true` (no interactive EULA), G1GC + String Deduplication.
    - `-Dreloaded.classes.hotswap=true` and `-XX:+AllowEnhancedClassRedefinition` to improve method-body hot swap in IDEs.
  - Program arg `--nogui` is set by default.
  - The run task downloads `lanbroadcaster` via Modrinth in order to help developers connect to the server.

- Java Launcher for Run Tasks
  - All `xyz.jpenilla.runtask.task.AbstractRun` tasks use the Java Toolchains API pinned to JetBrains JDK 25. If you see IDE/runtime JDK mismatches, re-sync Gradle so the toolchain is applied.

- Runtime Files/Directories
  - The development server writes into `run/`. This includes `plugins/`, `server.properties`, per-world data, and logs.
  - Plugin development config ends up in `run/plugins/BlockPatterns/` when the plugin is installed/loaded. Do not commit the `run/` tree; it is ignored by `.gitignore`.

4. Resource Generation and Configuration Handling
- plugin.yml Generation
  - The `bukkitPluginYaml { ... }` block in `build.gradle.kts` generates plugin.yml. Key settings:
    - `main = "au.id.rleach.blockpatterns.BlockPatternsPlugin"`
    - `apiVersion = "1.21.10"`
    - `load = STARTUP`, `authors = ["ryan_the_leach"]`
  - Name/version/description are taken from Gradle’s `group`, `version`, and `description`.
  - Avoid manually committing a static `plugin.yml`; rely on the resource-factory to keep it in sync with Gradle metadata.

- Config Bootstrapping (BoostedYAML)
  - On startup, `BlockPatternsPlugin` ensures a `config.yml` exists in the plugin’s data folder (under `run/plugins/BlockPatterns/` at dev time).
  - It loads with `YamlDocument.create(...)` using `defaultConfig.yml` as defaults and enables comment preservation.
  - Auto-updater/versioning is enabled via `BasicVersioning("version")` with `UpdaterSettings.autoSave = true`.
    - Ensure `defaultConfig.yml` contains a top-level `version` key and increment it when you change defaults; BoostedYAML will merge and auto-save.
  - If `defaultConfig.yml` is missing from resources, the plugin falls back to creating an empty `config.yml` (logged error indicates the failure path).

5. Logging and Messaging
- Logging should use log4j2 where possible.
- Player/console messaging uses Adventure:
  - A `BukkitAudiences` instance is created on enable and closed on disable.
  - Messages use MiniMessage (`MiniMessage.miniMessage()`).
  - Be cautious with MiniMessage trust boundaries if you ever interpolate input.

6. Shading/Dependency Guidance
- Mark runtime-provided APIs as `compileOnly` (e.g., `adventure-api` is provided by Paper). Keep shaded libraries pinned via `implementation` so Shadow includes them.
- If you add new shaded libraries, also add a `relocate("<pkg>")` entry in `tasks.shadowJar { ... }` to prevent collisions.
- After adding or changing relocations, test on a clean server to catch any ServiceLoader or resource path mismatches. Service files are already merged with `mergeServiceFiles()`.

7. Hot-swap and Debugging Tips
- With the provided JVM args, IntelliJ IDEA can hot-swap method bodies reliably while the dev server is running.
- Structural changes (adding methods/fields/classes) are still limited by the JVM. If you require deeper hot-swap:
  - Consider DCEVM + HotswapAgent; verify compatibility with JDK 25 first (support may lag this toolchain).
  - Alternatively, use incremental rebuild + `/reload confirm` or a restart of the dev run task.

9. Repository Hygiene
- `.gitignore` already excludes `/.gradle/`, `/.idea/`, `/build/`, and `/run/` and common IDE outputs. Do not commit the `run/` directory or server artifacts.
- Keep `defaultConfig.yml` in `src/main/resources/`. Avoid committing the generated `config.yml` from the dev server; it belongs in `run/plugins/BlockPatterns/`.

10. Versioning and Releases
- Update the Gradle `version` before tagging a release. This updates the generated plugin.yml automatically.
- For release artifacts targeting production servers:
  - Build shaded jar: `./gradlew shadowJar`
  - Reobfuscate for distribution: `./gradlew reobfJar`
  - Distribute the reobfuscated shaded jar from `build/libs/` (ensure you pick the correct classifier/newest timestamp).

11. Common Pitfalls (Project-Specific)
- Ensure your IDE uses the Gradle-managed JDK 25 toolchain; manually pointing the IDE to a different JDK can cause subtle classfile version issues.
- If you change the Minecraft/Paper version, update BOTH:
  - `paperweightDevelopmentBundle("io.papermc.paper:dev-bundle:<VERSION>")`
  - `tasks.runServer { minecraftVersion("<VERSION>") }`
  These must remain in sync.
- When altering default config structure, bump `version` in `defaultConfig.yml` so BoostedYAML’s updater merges values for existing installs.

12. Project Goals
- To create a library that is able to detect specific multi-block designs that players build, like runes, netherportals, beacons, etc and trigger code.
- Performance, pre-compute as much as possible, use nms (minecraft internals) when it causes less object allocations to be made in the hot path.
- use modern java features over traditional where possible, for the sake of teaching
- Favor well engineered data structures over dumb tight loops.
- It should be self measuring and self optimizing.
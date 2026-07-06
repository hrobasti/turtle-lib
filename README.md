# turtle-lib ⚙️

Shared helper library for building Minecraft Paper plugins faster. Grab the modules you need, drop them into your plugin, and keep boilerplate out of your main project.

## Core Helpers

- **UpdateChecker 🔔** – Async Modrinth/Hangar polling with provider priority + version filtering.
- **VersionComparator 📐** – Semantic + pre-release comparison with sensible fallbacks for suffixes.
- **ConfigWatcher 👀** – Lightweight file watcher that merges defaults and invokes safe main-thread callbacks.
- **StartupBanner 🎉** – MiniMessage banner loader for flashy enable logs.
- **MessageService 💬** – Locale sync + MiniMessage rendering (component, format, plain) with auto-prefix placeholders.
- **LangLoader 📦** – Scans bundled `lang/*.yml`, syncs them into plugin data folders, and merges new keys without overwriting custom edits.
- **ServerMatcher 🧭** – Declarative server-version guard that can warn or auto-disable plugins outside your supported range.

## Requirements

- Java 25+
- Gradle wrapper (included) targeting 9.6.1
- Paper API 26.2 on the compile classpath

## Quick Start

1. `./gradlew clean build` (or `gradlew.bat clean build`) → JAR lands in `build/libs/turtle-lib-<version>.jar`.
2. Include it via composite build (`includeBuild("../turtle-lib")`) or publish locally: `./gradlew publishToMavenLocal`.
3. Depend on it from your plugin: `implementation("com.github.hrobasti.turtlelib:turtle-lib:<version>")`.
4. Instantiate the helpers you need (e.g., `new ConfigWatcher.Builder(...)`, `MessageService`, etc.).

## Learn More

Deep dives for every helper (usage patterns, advanced settings, code samples) live in the turtle-lib wiki. Start at `wiki/home.md` to pick the article you need.

## License & Credits

- turtle-lib ships under a proprietary license (see `LICENSE`). Respect the terms when bundling it into your plugins.
- Third-party dependencies (MiniMessage/MIT, Gson/Apache 2.0, etc.) are listed in `THIRD_PARTY_LICENSES.md` with full texts under `src/main/resources/licenses/`.
- Portions of this library and its docs were drafted with AI assistance (e.g., GitHub Copilot) and reviewed before release.

# Third-party notices

TurtleLib by kroet.net contains no third-party software. Its jar `turtle-lib-paper-<version>.jar` holds only TurtleLib's own code and its license as `META-INF/LICENSE` (MIT). A plugin that shades TurtleLib ships that license text with it, as the MIT License requires.

## Trademarks

- Minecraft and its names belong to Mojang Studios and Microsoft. TurtleLib is not an official Minecraft product and is not approved by or associated with Mojang or Microsoft.
- Paper, Hangar and Modrinth are named only to identify the platforms and services TurtleLib works with; the names belong to their owners.

## Not shipped

Never part of the TurtleLib jar; versions in `gradle/libs.versions.toml`:

- Provided at runtime by Paper: the Paper API (Paper's own license terms) with Adventure and MiniMessage (MIT) and Gson (Apache-2.0). Plugins that use TurtleLib get them the same way and do not shade them.
- Used only for tests: JUnit 6 (Jupiter and Platform Launcher; EPL-2.0).
- Used only for the build's null check: the JSpecify annotations (Apache-2.0, compile-only) and the Eclipse compiler ECJ (EPL-2.0).

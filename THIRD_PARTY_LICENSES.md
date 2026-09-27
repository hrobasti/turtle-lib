# Third-Party Licenses

| Dependency                                                                                             | Version (current)                    | License            | Upstream                                  | Used by                                                                                                                                |
| ------------------------------------------------------------------------------------------------------ | ------------------------------------ | ------------------ | ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| Adventure (`net.kyori:adventure-api`, `adventure-text-minimessage`, `adventure-text-serializer-plain`) | provided by Paper (currently 5.2.0)  | MIT License        | https://github.com/KyoriPowered/adventure | `MessageService` (components, MiniMessage, plain text), `StartupBanner` (MiniMessage), `LocaleFileChecker` (strict MiniMessage parse). |
| `com.google.code.gson:gson`                                                                            | provided by Paper (currently 2.14.0) | Apache License 2.0 | https://github.com/google/gson            | `UpdateChecker` (parsing Modrinth/Hangar responses; `ProviderResult#metadata()`).                                                      |

Neither dependency is bundled into a TurtleLib artifact: both reach the compile classpath only through the `compileOnly` Paper API, Paper provides them at runtime, and their versions follow Paper's. Plugins consuming TurtleLib get them the same way and shouldn't declare or shade their own copies; see `timberella-plugin` and `underwatertrees-plugin` for the pattern.

TurtleLib itself is MIT-licensed (see `LICENSE`). A plugin that shades TurtleLib into its jar has to include TurtleLib's copyright and license notice, as the MIT License requires.

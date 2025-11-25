# Third-Party Licenses

| Dependency | Version (current) | License | Upstream | Notes |
| --- | --- | --- | --- | --- |
| net.kyori:adventure-text-minimessage | 4.17.0 | MIT License | https://github.com/KyoriPowered/adventure | Used by `MessageService` to render MiniMessage strings and prefixes. |
| com.google.code.gson:gson | 2.10.1 | Apache License 2.0 | https://github.com/google/gson | Lightweight JSON parsing for helper utilities. |

The corresponding license texts are shipped alongside the consuming plugins under `src/main/resources/licenses/` when shaded.

Because turtle-lib is bundled through composite builds, please keep these notices intact whenever you redistribute the library or plugins that embed it.

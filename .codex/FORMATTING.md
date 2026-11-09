# Source formatting

Use Java 21 to run the Gradle wrapper. Apply formatting with `./gradlew spotlessApply`
and verify it with `./gradlew spotlessCheck`. The ordinary Gradle `check` task also
checks formatting.

- Java: pinned google-java-format, using [Google Java Style](https://google.github.io/styleguide/javaguide.html)
  (two-space indentation and a 100-column target). Includes main, test and probe sources.
- JSON: Gson with two-space indentation, preserving member order and values. Includes
  resources, helper data, formatter configuration and recorded JSON evidence.
- Gradle: pinned Groovy Eclipse formatter.
- JavaScript: pinned standalone Biome executable, with two-space indentation and
  a 100-column target. Gradle downloads the executable without Node or npm.
- Shell: trailing whitespace cleanup and a final newline; preserve existing layout.

Python helper tools are intentionally excluded. Generated build output, caches,
runtime worlds, backups and prompt material are outside formatter targets. Formatter
dependencies are development tooling and are not packaged in the mod.

This is a formatting policy, not a requirement to rename APIs or otherwise refactor
behavior to satisfy every non-formatting recommendation in the style guide.

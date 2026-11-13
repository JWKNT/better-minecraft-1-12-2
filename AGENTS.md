# Better 1.12.2 mod and page

The mod is in `src/`, its tools in `tools/`, and `README.md` describes every feature and file.
`index.html` and `assets/` are the jehlp.net page at `/better-minecraft-1-12-2/`.

## The page

Read `../site-theme/PHILOSOPHY.md`, `../site-theme/docs/DESIGN-SYSTEM.md` and
`../site-theme/docs/WRITING-STYLE.md` before a change to the page.
Use ASD-STE100 Issue 9 for the page text. Describe the style as ASD-STE100-guided.
`assets/mark.png` and `assets/favicon.png` are copies of the `better-minecraft-1-12-2` mark in site-theme.
The pictures in `assets/pictures/` are JPEG copies of files in `screenshots/`.

## The mod

- Build with `./gradlew build` and a JDK 25 for Gradle. Put `BetterQuesting-3.5.329.jar` in `libs/` first.
- The mod must stay safe on a dedicated server: `Better112`, `BetterConfig`, `LocateBiome`, the worldgen
  classes (`better112.world`) and the content classes except `ContentClient` load there. Client code goes
  through `ClientSetup`. Worldgen reaches the imported blocks only through `better112.world.feature.Imported`.
- A server with the mod requires the same mod version on every client (`Better112.checkRemote`). Both sides
  must read the same jars (`GameJars`: `better112/modern_mods` first), or Forge refuses the player; compare
  the "Modern content: ..., signature" log lines.
- Test server work on a local dedicated server built from Mojang's 1.12.2 server jar (ask before downloading
  it and before accepting its EULA), with a client from the DJ2 clone joining through the self-test's `join`
  step. Never use join-on-launch in DJ2: Universal Tweaks leaves "Logging in..." stuck.
- Test with the self-test (`-Dbetter112.autotest=<script>`, steps in `test/AutoTest.java`) in a copy of a game
  folder, never in a player's own instance.
- A new release names the same version in `gradle.properties`, the release tag, the jar name, `README.md`
  and the page.

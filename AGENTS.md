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
- The mod must stay safe on a dedicated server: only `Better112`, `BetterConfig` and `LocateBiome` load there.
  Client code goes through `ClientSetup`.
- Test with the self-test (`-Dbetter112.autotest=<script>`, steps in `test/AutoTest.java`) in a copy of a game
  folder, never in a player's own instance.
- A new release names the same version in `gradle.properties`, the release tag, the jar name, `README.md`
  and the page.

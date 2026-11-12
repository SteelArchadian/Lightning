# Lightning ⚡

A performance profiler for Minecraft 1.21.1 (NeoForge). It records frames, server ticks, the graphics card,
Java code, memory, mobs, machines and chunks on one timeline, then opens a report in your browser that
tells you, in plain English, what is slowing the game down and which mod is responsible.

## Use it
- `/lightning report` — record everything for 60 seconds and open the report.
- `/lightning profiler start` … `/lightning profiler stop` — record for as long as you like.
  Type `--` after `start` to see the options.
- `/lightning mark <name>` — mark a moment ("boss fight") to compare phases.
- `/lightning heap deep` — which mod is holding which memory.

## Slow start-up or world joins
Add to the game's Java arguments:

```
-Dlightning.startup=join -XX:StartFlightRecording=name=lightning,settings=profile,class-loading=true
```

Lightning then records from the moment Java starts until you're in a world (or `title` to stop at the
title screen; add `,quit` to close the game afterwards, e.g. `join,10,quit`). The report's Loading tab
shows every step (finding mods, building mods, loading resources, joining), how long each took, the
slowest resource loaders, which mods' mixins made loading code slow, and what the main thread waited on.
The second flag is optional; without it the time before Lightning loads comes from the game's logs only.
To record every world join instead, turn on "Record world joins" in the config.

Each recording is saved as a `.lightning` file (a zip with `profile.json`, `summary.md` and `results.json`)
in `<game folder>/lightning/captures`, plus an HTML report.

## For other mods
`dev.lightning.api.LightningApi` starts and stops recordings, marks phases and returns the saved files.
Check `ModList.get().isLoaded("lightning")` first. `loadingStep(name, msSinceJvmStart, phase)` adds your own
steps to the loading timeline.

## Build
Java 21. `./gradlew build` (jar in `build/libs`). `./gradlew test` and `./gradlew runGameTestServer` run the tests.

## Licence
LGPL-3.0-only — see [LICENSE](LICENSE) (the LGPL) and [COPYING](COPYING) (the GPL it builds on).
You can use, change and share Lightning; changed versions of Lightning itself must stay open under the same licence. Made with AI assistance.

## Feedback, bugs and feature ideas welcome
This mod is actively developed, and your input shapes what comes next. Found a bug, have an idea for a feature, or think something could work better? **Please share it** in **[Issues](https://github.com/SteelArchadian/Lightning/issues)**. Every report and suggestion gets read, and feature requests are genuinely encouraged: good ideas get built.

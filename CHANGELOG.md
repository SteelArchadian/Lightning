# Changelog

## 0.5.21-alpha
- Warns when other programs (another game, a build) use a lot of CPU during a capture; `results.machineLoad`.
- Shows how long the game waits for threads to reach safepoints, which code ran during the longest waits, and how often slow frames overlap them (`results.safepoints`).
- Explains the longest garbage collection (kind, objects that could not be moved, slowest phase, references) with the gc part.
- The deep memory report breaks JDK-held memory down by the class and field holding it; `-Dlightning.bigAllocs=true` names the code making very large allocations.
- Array memory by allocating code is estimated by sample count, so one large buffer no longer looks huge.
- Shader pack loads, pipeline builds and the frame that leaves the world no longer count as lag spikes.
- Mod metadata names SteelArchadian as author and links the issue tracker.

## 0.5.0 – 0.5.12-alpha
- Slow-frame analysis: what slow frames have in common compared with normal ones; menu frames are kept apart from gameplay. Slow server ticks get the same analysis.
- Event-listener cost by mod (`listeners[]`), naming the real poster behind mixin bridges.
- Settings in the shared Config Panel screen when it is installed.
- Records deoptimisations, class defines, slow compilations and VM operations, and uses them to explain long pauses.
- Automatic and API captures take an end-of-capture memory snapshot (`-Dlightning.heap=false` to skip); plain arrays split by allocating code.
- Hardening: hooks can't crash the game; bad flags or config fall back to defaults; damaged or disk-full captures still produce a report; an unwritable output folder falls back to the captures folder.
- About 6x less writer CPU; profile.json about a third smaller on long captures.

## 0.4.0 – 0.4.2-alpha
- Per-mod block for comparing PCs (`crosspc`), and game-thread waits by mod.
- Iris passes always GPU-timed; GPU queue lag and frame gap; finding "CPU and GPU take turns".

## 0.3.1 – 0.3.2-alpha
- CPU, lag-spike and allocation blame go to the code that actually did the work; mods that only passed the call along are listed separately.
- 1% and 0.1% lows; `--key=value` options, with suggestions while typing.

## 0.3.0-alpha
- **Start-up and world-join profiling.** `-Dlightning.startup=join` records from the moment Java starts
  until you're in a world (`title` stops at the title screen; `,quit` closes the game afterwards). With
  `-XX:StartFlightRecording=name=lightning,settings=profile,class-loading=true` the time before Lightning
  loads gets code-level detail too; without it, that part comes from the game's logs.
- New **Loading** tab: every loading step with its time, the slowest resource and data pack loaders,
  class-loading time by mod (including which mods' mixins changed the classes), and what the main thread
  was doing every 10 ms (running, loading classes, waiting, graphics driver, files).
- "Record world joins" config option: records each join from the click until the world shows.
- API: `LightningApi.loadingStep(...)` lets other mods add their own steps to the loading timeline (API version 2).
- Saving long captures no longer holds the whole report in memory.
- Licence changed from MIT to LGPL-3.0-only.

## 0.2.0-alpha
- Rebuilt from scratch: frames, ticks, GPU per render step (including Iris shader passes and Voxy), Java
  sampling, memory and allocation by mod, mobs/machines/chunks, logs on one timeline.
- `.lightning` file plus a browser report with plain-English "biggest contenders".
- `/lightning report`, `/lightning profiler start|stop`, `/lightning mark`, `/lightning heap [deep]`.
- Crash recovery of unfinished captures.

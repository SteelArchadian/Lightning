# Changelog

## 0.3.0-alpha (unreleased)
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

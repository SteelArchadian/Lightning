package dev.lightning.capture;

import java.util.List;

/**
 * What a loading capture adds to the usual data.
 *
 * @param steps                 loading phases and milestones, in time order
 * @param reloads               resource and data pack reloads with per-listener times
 * @param wall                  wall-clock samples of the loading threads (from when Lightning loaded)
 * @param mixins                which mods' mixins changed which classes
 * @param jfrFromStartup        whether JVM sampling covers the time before Lightning loaded
 * @param jfrStartupFromNanos   when that JVM-started recording began
 * @param lightningLoadedNanos  when Lightning itself was loaded
 * @param earlyLogLines         log lines read back from before Lightning loaded
 */
public record LoadSnapshot(List<LoadTimeline.Milestone> steps, List<LoadTimeline.Reload> reloads, WallSampler wall, MixinScan mixins,
                           boolean jfrFromStartup, long jfrStartupFromNanos, long lightningLoadedNanos, int earlyLogLines) {}

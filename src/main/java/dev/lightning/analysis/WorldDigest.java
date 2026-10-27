package dev.lightning.analysis;

import dev.lightning.capture.Track;
import dev.lightning.capture.WorldStats;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/** Entity and block-entity tick costs merged across threads, by level, chunk, type and instance. */
public final class WorldDigest {
    public static final int TOP = 300;

    public static final class Chunk {
        public final int x, z;
        public long entityTicks, entityNanos, blockEntityTicks, blockEntityNanos;
        /** type → {ticks in this chunk, nanos in this chunk, instances last seen here} */
        public final Map<String, long[]> contributors = new HashMap<>();

        Chunk(int x, int z) {
            this.x = x;
            this.z = z;
        }

        public long nanos() {
            return entityNanos + blockEntityNanos;
        }
    }

    public record Instance(String type, String kind, int x, int y, int z, int id, long ticks, long nanos, long max) {}

    public static final class TypeTotal {
        public final String type, kind;
        public long ticks, nanos, max, instances;

        TypeTotal(String type, String kind) {
            this.type = type;
            this.kind = kind;
        }
    }

    public static final class Level {
        public final String dimension;
        public final boolean client;
        public final Map<Long, Chunk> chunks = new HashMap<>();
        public final List<Instance> instances = new ArrayList<>();
        public final Map<String, TypeTotal> types = new HashMap<>();
        public long entityNanos, blockEntityNanos, entityTicks, blockEntityTicks;

        Level(String dimension, boolean client) {
            this.dimension = dimension;
            this.client = client;
        }

        public List<Chunk> topChunks() {
            return chunks.values().stream().sorted(Comparator.comparingLong(Chunk::nanos).reversed()).limit(TOP).toList();
        }

        public List<Instance> topInstances() {
            return instances.stream().sorted(Comparator.comparingLong(Instance::nanos).reversed()).limit(TOP).toList();
        }

        public List<TypeTotal> topTypes() {
            return types.values().stream().sorted(Comparator.comparingLong((TypeTotal t) -> t.nanos).reversed()).limit(TOP).toList();
        }
    }

    public final List<Level> levels = new ArrayList<>();

    public static WorldDigest of(List<Track> tracks) {
        WorldDigest digest = new WorldDigest();
        Map<String, Level> byKey = new HashMap<>();
        for (Track track : tracks) {
            for (WorldStats.LevelStats stats : track.world.levels.values()) {
                Level level = byKey.computeIfAbsent((stats.client ? "client:" : "server:") + stats.dimension, k -> {
                    Level l = new Level(stats.dimension, stats.client);
                    digest.levels.add(l);
                    return l;
                });
                merge(level, stats);
            }
        }
        digest.levels.sort(Comparator.comparingLong((Level l) -> l.entityNanos + l.blockEntityNanos).reversed());
        return digest;
    }

    private static void merge(Level level, WorldStats.LevelStats stats) {
        for (var e : stats.chunks.long2ObjectEntrySet()) {
            long key = e.getLongKey();
            WorldStats.ChunkStat c = e.getValue();
            Chunk chunk = level.chunks.computeIfAbsent(key, k -> new Chunk((int) (key >> 32), (int) key));
            chunk.entityTicks += c.entityTicks;
            chunk.entityNanos += c.entityNanos;
            chunk.blockEntityTicks += c.blockEntityTicks;
            chunk.blockEntityNanos += c.blockEntityNanos;
            level.entityNanos += c.entityNanos;
            level.entityTicks += c.entityTicks;
            level.blockEntityNanos += c.blockEntityNanos;
            level.blockEntityTicks += c.blockEntityTicks;
            for (var t : c.byType.entrySet()) {
                String type = t.getKey() instanceof net.minecraft.world.entity.EntityType<?> et
                        ? String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(et)) : String.valueOf(t.getKey());
                long[] agg = chunk.contributors.computeIfAbsent(type, k -> new long[3]);
                agg[0] += t.getValue()[0];
                agg[1] += t.getValue()[1];
            }
        }
        for (var e : stats.entities.int2ObjectEntrySet()) {
            WorldStats.EntityStat s = e.getValue();
            String type = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(s.type));
            level.instances.add(new Instance(type, "entity", s.x, s.y, s.z, e.getIntKey(), s.ticks, s.nanos, s.max));
            add(level, type, "entity", s.ticks, s.nanos, s.max, s.x >> 4, s.z >> 4);
        }
        for (var e : stats.blockEntities.long2ObjectEntrySet()) {
            WorldStats.BlockEntityStat s = e.getValue();
            long pos = e.getLongKey();
            int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
            level.instances.add(new Instance(s.type, "block entity", x, y, z, 0, s.ticks, s.nanos, s.max));
            add(level, s.type, "block entity", s.ticks, s.nanos, s.max, x >> 4, z >> 4);
        }
    }

    private static void add(Level level, String type, String kind, long ticks, long nanos, long max, int cx, int cz) {
        TypeTotal total = level.types.computeIfAbsent(kind + "|" + type, k -> new TypeTotal(type, kind));
        total.ticks += ticks;
        total.nanos += nanos;
        total.max = Math.max(total.max, max);
        total.instances++;
        // Instance count per chunk uses the last known position.
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        Chunk chunk = level.chunks.get(key);
        if (chunk != null) chunk.contributors.computeIfAbsent(type, k -> new long[3])[2]++;
    }
}

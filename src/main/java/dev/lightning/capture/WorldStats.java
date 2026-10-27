package dev.lightning.capture;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

/**
 * Exact (not sampled) entity and block-entity tick costs gathered on one thread.
 * Every tick is counted, not only slow ones, so per-chunk totals add up.
 */
public final class WorldStats {
    public final Map<Level, LevelStats> levels = new IdentityHashMap<>();
    private Level lastLevel;
    private LevelStats lastStats;

    LevelStats level(Level level) {
        if (level == lastLevel) return lastStats;
        LevelStats stats = levels.get(level);
        if (stats == null) {
            stats = new LevelStats(level.dimension().location().toString(), level.isClientSide());
            levels.put(level, stats);
        }
        lastLevel = level;
        lastStats = stats;
        return stats;
    }

    void entity(Level level, Entity entity, long nanos) {
        LevelStats stats = level(level);
        int id = entity.getId();
        EntityStat stat = stats.entities.get(id);
        if (stat == null) {
            stat = new EntityStat(entity.getType());
            stats.entities.put(id, stat);
        }
        stat.ticks++;
        stat.nanos += nanos;
        if (nanos > stat.max) stat.max = nanos;
        int x = entity.getBlockX(), y = entity.getBlockY(), z = entity.getBlockZ();
        stat.x = x;
        stat.y = y;
        stat.z = z;
        ChunkStat chunk = stats.chunk(x >> 4, z >> 4);
        chunk.entityTicks++;
        chunk.entityNanos += nanos;
        chunk.add(stat.type, nanos);
    }

    void blockEntity(Level level, TickingBlockEntity ticker, long nanos) {
        LevelStats stats = level(level);
        long pos = ticker.getPos().asLong();
        BlockEntityStat stat = stats.blockEntities.get(pos);
        if (stat == null) {
            stat = new BlockEntityStat(ticker.getType());
            stats.blockEntities.put(pos, stat);
        }
        stat.ticks++;
        stat.nanos += nanos;
        if (nanos > stat.max) stat.max = nanos;
        ChunkStat chunk = stats.chunk(net.minecraft.core.BlockPos.getX(pos) >> 4, net.minecraft.core.BlockPos.getZ(pos) >> 4);
        chunk.blockEntityTicks++;
        chunk.blockEntityNanos += nanos;
        chunk.add(stat.type, nanos);
    }

    public static final class LevelStats {
        public final String dimension;
        public final boolean client;
        public final Int2ObjectOpenHashMap<EntityStat> entities = new Int2ObjectOpenHashMap<>();
        public final Long2ObjectOpenHashMap<BlockEntityStat> blockEntities = new Long2ObjectOpenHashMap<>();
        public final Long2ObjectOpenHashMap<ChunkStat> chunks = new Long2ObjectOpenHashMap<>();
        private long lastChunkKey = Long.MIN_VALUE;
        private ChunkStat lastChunk;

        LevelStats(String dimension, boolean client) {
            this.dimension = dimension;
            this.client = client;
        }

        ChunkStat chunk(int cx, int cz) {
            long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
            if (key == lastChunkKey) return lastChunk;
            ChunkStat stat = chunks.get(key);
            if (stat == null) {
                stat = new ChunkStat();
                chunks.put(key, stat);
            }
            lastChunkKey = key;
            lastChunk = stat;
            return stat;
        }
    }

    public static final class EntityStat {
        public final EntityType<?> type;
        public long ticks, nanos, max;
        public int x, y, z;

        EntityStat(EntityType<?> type) {
            this.type = type;
        }
    }

    public static final class BlockEntityStat {
        public final String type;
        public long ticks, nanos, max;

        BlockEntityStat(String type) {
            this.type = type;
        }
    }

    public static final class ChunkStat {
        public long entityTicks, entityNanos, blockEntityTicks, blockEntityNanos;
        /** Type (an EntityType, or a block-entity type name) → {ticks, nanos}, as ticked in this chunk. */
        public final java.util.HashMap<Object, long[]> byType = new java.util.HashMap<>(8);

        void add(Object type, long nanos) {
            long[] t = byType.get(type);
            if (t == null) byType.put(type, t = new long[2]);
            t[0]++;
            t[1] += nanos;
        }
    }
}

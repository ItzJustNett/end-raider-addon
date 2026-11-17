package com.endraider.util;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Scans loaded chunks for End island surfaces (top end stone per column, on a coarse grid),
 * groups them into islands and picks where to go next along a heading.
 */
public class IslandScanner {
    public static final int STEP = 2;

    /** Grid column -> surface y (y of the end stone block). */
    private final Long2IntOpenHashMap land = new Long2IntOpenHashMap();
    public BlockPos cityBlock;

    public record Plan(BlockPos target, boolean bridgeStraight, int gap) {}

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static int kx(long k) { return (int) (k >> 32); }
    private static int kz(long k) { return (int) k; }

    public void scan(int chunkRadius) {
        land.clear();
        cityBlock = null;
        double bestCity = Double.MAX_VALUE;
        int pcx = mc.player.blockPosition().getX() >> 4, pcz = mc.player.blockPosition().getZ() >> 4;
        int minY = mc.level.getMinY();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int cx = pcx - chunkRadius; cx <= pcx + chunkRadius; cx++) {
            for (int cz = pcz - chunkRadius; cz <= pcz + chunkRadius; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                for (int lx = 0; lx < 16; lx += STEP) {
                    for (int lz = 0; lz < 16; lz += STEP) {
                        int x = (cx << 4) + lx, z = (cz << 4) + lz;
                        int y = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                        if (y <= minY) continue;

                        // skip chorus on top of islands
                        for (int i = 0; i < 24 && y > minY; i++) {
                            Block b = mc.level.getBlockState(m.set(x, y, z)).getBlock();
                            if (b == Blocks.CHORUS_PLANT || b == Blocks.CHORUS_FLOWER) y--;
                            else break;
                        }
                        Block b = mc.level.getBlockState(m.set(x, y, z)).getBlock();
                        if (b == Blocks.END_STONE) {
                            land.put(key(x, z), y);
                        } else if (b == Blocks.PURPUR_BLOCK || b == Blocks.PURPUR_PILLAR || b == Blocks.END_STONE_BRICKS) {
                            double d = mc.player.distanceToSqr(x, y, z);
                            if (d < bestCity) {
                                bestCity = d;
                                cityBlock = new BlockPos(x, y, z);
                            }
                        }
                    }
                }
            }
        }
    }

    private long nearestLand(int px, int pz) {
        long best = Long.MIN_VALUE;
        double bd = Double.MAX_VALUE;
        for (long k : land.keySet()) {
            double dx = kx(k) - px, dz = kz(k) - pz;
            double d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = k; }
        }
        return bd <= 64 ? best : Long.MIN_VALUE;
    }

    /**
     * @param hx,hz         unit heading
     * @param maxGap        islands further than this (in blocks of bridge) are ignored
     * @param minAdvance    target must be at least this much further along heading than the player
     */
    public Plan plan(double hx, double hz, int maxGap, int minAdvance) {
        int px = mc.player.blockPosition().getX(), pz = mc.player.blockPosition().getZ();
        long start = nearestLand(px, pz);
        if (start == Long.MIN_VALUE) return null;

        // flood fill current island
        LongOpenHashSet island = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        island.add(start);
        queue.enqueue(start);
        List<long[]> boundary = new ArrayList<>();
        while (!queue.isEmpty() && island.size() < 60000) {
            long k = queue.dequeueLong();
            int x = kx(k), z = kz(k), y = land.get(k);
            boolean edge = false;
            for (int[] d : new int[][]{{STEP, 0}, {-STEP, 0}, {0, STEP}, {0, -STEP}}) {
                long n = key(x + d[0], z + d[1]);
                if (!land.containsKey(n) || Math.abs(land.get(n) - y) > 4) { edge = true; continue; }
                if (island.add(n)) queue.enqueue(n);
            }
            if (edge) boundary.add(new long[]{x, z});
        }

        double playerProj = px * hx + pz * hz;

        // farthest edge of the current island along heading
        long far = start;
        double farProj = -Double.MAX_VALUE;
        for (long k : island) {
            double p = kx(k) * hx + kz(k) * hz;
            if (p > farProj) { farProj = p; far = k; }
        }

        // only the front part of the island is a sensible take-off point
        List<long[]> front = new ArrayList<>();
        for (long[] b : boundary) if (b[0] * hx + b[1] * hz >= farProj - 48) front.add(b);

        long best = Long.MIN_VALUE;
        double bestScore = Double.MAX_VALUE;
        int bestGap = 0;
        int i = 0;
        for (long k : land.keySet()) {
            if (island.contains(k)) continue;
            if ((i++ & 1) == 1) continue; // subsample
            int x = kx(k), z = kz(k);
            double proj = x * hx + z * hz;
            double adv = proj - playerProj;
            if (adv < minAdvance) continue;
            double lateral = Math.abs(-x * hz + z * hx - (-px * hz + pz * hx));
            if (lateral > adv * 1.2 + 24) continue; // keep roughly the heading

            double gap = Double.MAX_VALUE;
            for (long[] b : front) {
                double dx = b[0] - x, dz = b[1] - z;
                double d = dx * dx + dz * dz;
                if (d < gap) gap = d;
            }
            gap = Math.sqrt(gap);
            if (gap > maxGap) continue;

            // fewest blocks first, small reward for progress
            double score = gap - 0.15 * adv;
            if (score < bestScore) {
                bestScore = score;
                best = k;
                bestGap = (int) gap;
            }
        }

        if (best != Long.MIN_VALUE) {
            return new Plan(new BlockPos(kx(best), land.get(best) + 1, kz(best)), false, bestGap);
        }
        // nothing reachable ahead: walk to the far edge and bridge straight
        return new Plan(new BlockPos(kx(far), land.get(far) + 1, kz(far)), true, 0);
    }
}

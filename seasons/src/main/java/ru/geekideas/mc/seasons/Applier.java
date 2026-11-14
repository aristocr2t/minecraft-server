package ru.geekideas.mc.seasons;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.Material;

/**
 * Применение сезона по карте: читаем original_biome из базы и ставим нужный биом в мир.
 * Новые клетки не появляются — карту строит Scanner.
 */
final class Applier extends Pass {

    private final Season season;
    private final Map<String, String> goals;
    private final List<String> names;
    private final List<long[]> queue;
    private final int commitEvery;
    private int index;
    private int sinceCommit;

    Applier(SeasonsPlugin plugin, World world, Store store, Season season, int maxChunks, int budgetMillis,
            int radiusBlocks, int commitEvery) {
        super(plugin, world, store, maxChunks, budgetMillis);
        this.season = season;
        this.goals = plugin.goals(season);
        this.names = new ArrayList<>(goals.keySet());
        this.commitEvery = Math.max(1, commitEvery);
        int limit = radiusBlocks <= 0 ? 0 : radiusBlocks >> 4;
        this.queue = new ArrayList<>();
        for (Map.Entry<Long, String> entry : store.chunks(worldName).entrySet()) {
            if (season.state.equals(entry.getValue())) {
                continue; // уже в этом состоянии
            }
            int chunkX = Store.chunkX(entry.getKey());
            int chunkZ = Store.chunkZ(entry.getKey());
            if (limit > 0 && (Math.abs(chunkX) > limit || Math.abs(chunkZ) > limit)) {
                continue;
            }
            queue.add(new long[]{chunkX, chunkZ});
        }
        queue.sort(Comparator.comparingLong((long[] pos) -> pos[0] * pos[0] + pos[1] * pos[1]));
    }

    /**
     * Наш ускоритель таяния: убирает слой снега, убивает наледь, превращает лёд в воду.
     * Ледники (PACKED_ICE, BLUE_ICE) не трогаем. Проход сверху вниз, чтобы снег оседал слоями.
     */
    private void thawChunk(Chunk chunk, int perChunk) {
        int done = 0;
        outer:
        for (int y = world.getMaxHeight() - 1; y >= world.getMinHeight(); y--) {
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    if (done >= perChunk) {
                        break outer;
                    }
                    Block block = chunk.getBlock(dx, y, dz);
                    Material type = block.getType();
                    if (type == Material.SNOW) {
                        block.setType(Material.AIR, false);
                        done++;
                    } else if (type == Material.FROSTED_ICE) {
                        block.setType(Material.AIR, false);
                        done++;
                    } else if (type == Material.ICE) {
                        block.setType(Material.WATER, false);
                        done++;
                    }
                }
            }
        }
    }

    @Override
    String label() {
        return season.name().toLowerCase(java.util.Locale.ROOT);
    }

    int planned() {
        return queue.size();
    }

    @Override
    boolean hasPending() {
        return index < queue.size();
    }

    @Override
    void dispatchNext() {
        long[] pos = queue.get(index++);
        int chunkX = (int) pos[0];
        int chunkZ = (int) pos[1];
        inFlight++;
        world.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, error) -> {
            inFlight--;
            if (error != null) {
                plugin.getLogger().warning("[" + label() + "] чанк " + chunkX + "," + chunkZ + ": " + error);
                return;
            }
            if (chunk == null) {
                return; // чанк из карты больше не существует
            }
            apply(chunk, chunkX, chunkZ);
        });
    }

    private void apply(Chunk chunk, int chunkX, int chunkZ) {
        List<Store.Cell> cells = store.cells(worldName, chunkX, chunkZ);
        if (cells.isEmpty()) {
            return;
        }
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int written = 0;
        int mapped = 0;
        for (Store.Cell cell : cells) {
            String goal = goals.get(cell.original());
            if (goal == null) {
                continue; // «родной» ледяной или неизменяемый биом
            }
            mapped++;
            Biome target = plugin.biome(goal);
            if (target == null) {
                continue;
            }
            int x = baseX + cell.cellX() * 4;
            int z = baseZ + cell.cellZ() * 4;
            int y = (cell.sectionY() << 4) + cell.cellY() * 4;
            if (world.getBiome(x, y, z).getKey().getKey().equals(goal)) {
                continue;
            }
            world.setBiome(x, y, z, target);
            written++;
        }
        if (written > 0) {
            world.refreshChunk(chunkX, chunkZ);
        }
        if (plugin.thawEnabled && season != Season.WINTER) {
            thawChunk(chunk, plugin.thawPerChunk);
        }
        int[] cellsByState = new int[Season.values().length];
        if (season == Season.SPRING || season == Season.RESTORE) {
            cellsByState[Season.RESTORE.ordinal()] = cells.size();
        } else {
            cellsByState[season.ordinal()] = mapped;
            cellsByState[Season.RESTORE.ordinal()] = cells.size() - mapped;
        }
        store.putCellState(worldName, chunkX, chunkZ, season.state, names);
        store.putChunkState(worldName, chunkX, chunkZ, season.state, cellsByState, written);
        chunks++;
        changed += written;
        if (chunks % 500 == 0) {
            plugin.getLogger().info("[" + label() + "] " + chunks + " чанков, изменено " + changed + " клеток");
        }
        if (++sinceCommit >= commitEvery) {
            store.commit();
            sinceCommit = 0;
        }
    }
}

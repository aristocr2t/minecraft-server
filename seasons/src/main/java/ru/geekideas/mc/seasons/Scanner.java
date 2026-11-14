package ru.geekideas.mc.seasons;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;

/**
 * Построение карты биомов. Список чанков берём из region-файлов мира (только существующие
 * чанки, ничего не генерируем) и идём кольцами от центра мира (0,0) наружу.
 * Уже просканированные чанки в список не попадают, поэтому рестарт продолжает работу сам.
 */
final class Scanner extends Pass {

    private final List<long[]> planned;
    private final int commitEvery;
    private final int logEvery;
    private int index;
    private int ring = -1;
    private int ringScanned;
    private int sinceCommit;
    private int cellsTotal;
    private final int mapChunks;

    Scanner(SeasonsPlugin plugin, World world, Store store, int maxChunks, int budgetMillis,
            int radiusBlocks, int commitEvery, int logEvery) {
        super(plugin, world, store, maxChunks, budgetMillis);
        this.commitEvery = Math.max(1, commitEvery);
        this.logEvery = Math.max(1, logEvery);
        this.planned = new ArrayList<>();

        Map<Long, String> known = store.chunks(worldName);
        this.mapChunks = known.size();
        this.cellsTotal = known.size() * plugin.cellsPerChunk();

        File regionDir = regionDir(world);
        Set<Long> existing = existingChunks(regionDir);
        int limit = radiusBlocks <= 0 ? 0 : radiusBlocks >> 4;
        for (long key : existing) {
            if (known.containsKey(key)) {
                continue; // уже в карте: второй раз не читаем
            }
            int chunkX = Store.chunkX(key);
            int chunkZ = Store.chunkZ(key);
            if (limit > 0 && (Math.abs(chunkX) > limit || Math.abs(chunkZ) > limit)) {
                continue;
            }
            planned.add(new long[]{Math.max(Math.abs(chunkX), Math.abs(chunkZ)), key});
        }
        planned.sort(Comparator.comparingLong((long[] row) -> row[0]).thenComparingLong(row -> row[1]));

        plugin.getLogger().info("[scan] чанков на диске %d, в карте уже %d, к сканированию %d,"
                        .formatted(existing.size(), known.size(), planned.size())
                + " кольца от 0 до " + (planned.isEmpty() ? "-" : planned.getLast()[0])
                + ", region: " + regionDir);
    }

    @Override
    String label() {
        return "scan";
    }

    @Override
    boolean hasPending() {
        return index < planned.size();
    }

    @Override
    void dispatchNext() {
        long[] entry = planned.get(index++);
        int nextRing = (int) entry[0];
        long key = entry[1];
        if (nextRing != ring) {
            if (ring >= 0) {
                store.putMeta("scan_ring", String.valueOf(nextRing));
                store.commit();
                plugin.getLogger().info("[scan] кольцо %d готово: новых чанков %d, в карте %d чанков / %d клеток, следующее кольцо %d"
                        .formatted(ring, ringScanned, mapChunks + chunks, cellsTotal, nextRing));
            }
            ring = nextRing;
            ringScanned = 0;
        }
        int chunkX = Store.chunkX(key);
        int chunkZ = Store.chunkZ(key);
        inFlight++;
        world.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, error) -> {
            inFlight--;
            if (error != null) {
                plugin.getLogger().warning("[" + label() + "] чанк " + chunkX + "," + chunkZ + ": " + error);
                return;
            }
            if (chunk == null) {
                return; // чанк из region-файла не загрузился: не генерируем
            }
            scan(chunk);
        });
    }

    @Override
    void saveMeta() {
        store.putMeta("scan_ring", String.valueOf(ring));
    }

    private void scan(Chunk chunk) {
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        List<Store.Cell> cells = new ArrayList<>(plugin.cellsPerChunk());
        for (int quartY = plugin.minQuartY(); quartY <= plugin.maxQuartY(); quartY++) {
            int y = quartY << 2;
            for (int dz = 0; dz < 4; dz++) {
                for (int dx = 0; dx < 4; dx++) {
                    Biome biome = world.getBiome(baseX + dx * 4, y, baseZ + dz * 4);
                    cells.add(new Store.Cell(y >> 4, dx, quartY & 3, dz, biome.getKey().getKey()));
                }
            }
        }
        store.insertChunk(worldName, chunk.getX(), chunk.getZ(), cells);
        chunks++;
        ringScanned++;
        cellsTotal += cells.size();
        if (chunks % logEvery == 0) {
            plugin.getLogger().info("[scan] сканирование: %d чанков, %d клеток, кольцо %d"
                    .formatted(mapChunks + chunks, cellsTotal, ring));
        }
        if (++sinceCommit >= commitEvery) {
            store.commit();
            sinceCommit = 0;
        }
    }

    @Override
    protected void onFinish() {
        store.putMeta("scan_complete", "1");
    }

    /** Каталог region-файлов мира: Paper хранит измерения отдельно от корня мира. */
    static File regionDir(World world) {
        File folder = world.getWorldFolder();
        File direct = new File(folder, "region");
        if (direct.isDirectory()) {
            return direct;
        }
        String dimension = switch (world.getEnvironment()) {
            case NETHER -> "the_nether";
            case THE_END -> "the_end";
            default -> "overworld";
        };
        return new File(new File(folder, "dimensions/minecraft/" + dimension), "region");
    }

    /** Существующие (сгенерированные) чанки по заголовкам region-файлов. */
    static Set<Long> existingChunks(File regionDir) {
        Set<Long> found = new HashSet<>();
        File[] files = regionDir.listFiles((dir, name) -> name.matches("r\\.-?\\d+\\.-?\\d+\\.mca"));
        if (files == null) {
            return found;
        }
        byte[] header = new byte[4096];
        for (File file : files) {
            String[] parts = file.getName().split("\\.");
            int regionX;
            int regionZ;
            try {
                regionX = Integer.parseInt(parts[1]);
                regionZ = Integer.parseInt(parts[2]);
            } catch (RuntimeException e) {
                continue;
            }
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.readFully(header);
            } catch (IOException e) {
                continue;
            }
            for (int slot = 0; slot < 1024; slot++) {
                int offset = ((header[slot * 4] & 0xFF) << 24) | ((header[slot * 4 + 1] & 0xFF) << 16)
                        | ((header[slot * 4 + 2] & 0xFF) << 8) | (header[slot * 4 + 3] & 0xFF);
                if ((offset >>> 8) == 0) {
                    continue; // слот пустой
                }
                found.add(Store.chunkKey(regionX * 32 + (slot % 32), regionZ * 32 + (slot / 32)));
            }
        }
        return found;
    }
}

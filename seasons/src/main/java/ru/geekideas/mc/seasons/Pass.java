package ru.geekideas.mc.seasons;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

/**
 * Базовый фоновый проход: порция чанков за тик с бюджетом времени.
 * Загрузка чанков асинхронная, работа с биомами — в колбэке на главном потоке.
 */
abstract class Pass implements Runnable {

    protected final SeasonsPlugin plugin;
    protected final World world;
    protected final Store store;
    protected final String worldName;
    private final int maxChunks;
    private final long budgetNanos;
    private final long startedAt = System.currentTimeMillis();
    private BukkitTask task;
    private boolean finished;
    protected int inFlight;
    protected int chunks;
    protected int changed;

    Pass(SeasonsPlugin plugin, World world, Store store, int maxChunks, int budgetMillis) {
        this.plugin = plugin;
        this.world = world;
        this.store = store;
        this.worldName = world.getName();
        this.maxChunks = Math.max(1, maxChunks);
        this.budgetNanos = Math.max(1, budgetMillis) * 1_000_000L;
    }

    abstract String label();

    /** Есть ли ещё что обрабатывать (без учёта уже запущенных чанков). */
    abstract boolean hasPending();

    /** Запустить следующий чанк; может ничего не запустить, если чанк уже в карте. */
    abstract void dispatchNext();

    /** Накопительное состояние на диск (метаданные кольца и т.п.). */
    void saveMeta() {
    }

    /** Вызывается только при нормальном завершении прохода (не при stop). */
    void onFinish() {
    }

    String progress() {
        return "%d чанков, изменено %d клеток".formatted(chunks, changed);
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
    }

    boolean isFinished() {
        return finished;
    }

    void stop() {
        if (finished) {
            return;
        }
        finished = true;
        if (task != null) {
            task.cancel();
        }
        saveMeta();
        store.commit();
        plugin.getLogger().info("[" + label() + "] остановлено: " + progress() + ", прогресс сохранён");
        plugin.passFinished(this, "остановлено: " + progress());
    }

    @Override
    public final void run() {
        if (finished) {
            return;
        }
        long deadline = System.nanoTime() + budgetNanos;
        try {
            while (inFlight < maxChunks && System.nanoTime() < deadline && hasPending()) {
                dispatchNext();
            }
            if (inFlight == 0 && !hasPending()) {
                finished = true;
                if (task != null) {
                    task.cancel();
                }
                saveMeta();
                onFinish();
                store.commit();
                String summary = "%d чанков, изменено %d клеток, %d c"
                        .formatted(chunks, changed, (System.currentTimeMillis() - startedAt) / 1000);
                plugin.getLogger().info("[" + label() + "] завершено: " + summary);
                plugin.passFinished(this, summary);
            }
        } catch (RuntimeException e) {
            finished = true;
            if (task != null) {
                task.cancel();
            }
            plugin.getLogger().severe("[" + label() + "] ошибка, проход остановлен: " + e);
            e.printStackTrace();
            try {
                store.commit();
            } catch (RuntimeException ignored) {
                // база уже в ошибке, повторно не врём
            }
            plugin.passFinished(this, "прервано ошибкой: " + e.getMessage());
        }
    }
}

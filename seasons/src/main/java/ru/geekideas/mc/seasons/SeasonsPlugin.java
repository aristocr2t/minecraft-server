package ru.geekideas.mc.seasons;

import java.io.File;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Сезонные биомы: карта исходных биомов в SQLite + операции по ней
 * (spring/summer/autumn/winter, restore). Схема и команды — в README.md.
 */
public final class SeasonsPlugin extends JavaPlugin implements TabExecutor {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss");
    private static final List<String> SUBCOMMANDS =
            List.of("scan", "spring", "summer", "autumn", "winter", "restore", "status", "stop");

    private final Map<String, String> spring = new LinkedHashMap<>();
    private final Map<String, String> summer = new LinkedHashMap<>();
    private final Map<String, String> autumn = new LinkedHashMap<>();
    private final Map<String, String> winter = new LinkedHashMap<>();
    private final Map<String, Biome> biomes = new HashMap<>();

    private Store store;
    private String storeError;
    private Pass pass;
    private String lastOp = "нет";
    private String scanState = "не запускалось";

    private int chunksPerTick;
    private int millisPerTick;
    private int commitEvery;
    private int logEvery;
    private int minY;
    private int maxY;
    private int scanMaxRadius;
    private boolean scanOnEnable;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        chunksPerTick = Math.max(1, getConfig().getInt("chunks-per-tick", 16));
        millisPerTick = Math.max(1, getConfig().getInt("max-millis-per-tick", 20));
        commitEvery = Math.max(1, getConfig().getInt("commit-every-chunks", 32));
        logEvery = Math.max(1, getConfig().getInt("log-every-chunks", 200));
        minY = getConfig().getInt("min-y", 40);
        maxY = getConfig().getInt("max-y", 320);
        scanMaxRadius = getConfig().getInt("scan-max-radius", 0);
        scanOnEnable = getConfig().getBoolean("scan-on-enable", true);

        if (minY < 0 || maxY <= minY) {
            getLogger().warning("min-y/max-y заданы неверно (" + minY + ".." + maxY + "), беру 40..320");
            minY = 40;
            maxY = 320;
        }
        readMap("spring-map", spring);
        readMap("summer-map", summer);
        readMap("autumn-map", autumn);
        readMap("winter-map", winter);
        getLogger().info("маппинг: spring " + spring.size() + ", summer " + summer.size()
                + ", autumn " + autumn.size() + ", winter " + winter.size()
                + "; высоты " + minY + ".." + maxY + " (" + layers() + " слоёв по 4 блока, "
                + cellsPerChunk() + " клеток на чанк)");

        File db = new File(getDataFolder(), "biomes.db");
        try {
            store = new Store(db);
            store.open();
            String saved = store.meta("scan_ring");
            if (saved != null) {
                scanState = "пауза на кольце " + saved;
            }
        } catch (SQLException | RuntimeException e) {
            storeError = "карта недоступна: " + e.getMessage();
            getLogger().severe(storeError);
            e.printStackTrace();
            store = null;
        }
        if (getCommand("seasons") != null) {
            getCommand("seasons").setExecutor(this);
            getCommand("seasons").setTabCompleter(this);
        }
        if (store != null && scanOnEnable) {
            Bukkit.getScheduler().runTaskLater(this, this::autoScan, 100L);
        }
    }

    @Override
    public void onDisable() {
        if (pass != null) {
            pass.stop();
            pass = null;
        }
        if (store != null) {
            store.close();
            store = null;
        }
    }

    private void autoScan() {
        if (pass == null && store != null) {
            getLogger().info("scan-on-enable: продолжаю построение карты от центра мира");
            startScan(scanMaxRadius);
        }
    }

    // ---------- конфиг ----------

    /** Таблица «исходный биом → биом сезона». Пустая секция = биом не меняется. */
    private void readMap(String path, Map<String, String> target) {
        ConfigurationSection section = getConfig().getConfigurationSection(path);
        if (section == null) {
            getLogger().warning("config: нет секции " + path);
            return;
        }
        for (String key : section.getKeys(false)) {
            String from = normalize(key);
            String to = normalize(section.getString(key, ""));
            if (biome(from) == null) {
                getLogger().warning(path + ": неизвестный биом " + key);
                continue;
            }
            if (biome(to) == null) {
                getLogger().warning(path + ": неизвестная цель " + section.getString(key) + " для " + key);
                continue;
            }
            target.put(from, to);
        }
    }

    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("minecraft:") ? trimmed.substring("minecraft:".length()) : trimmed;
    }

    Biome biome(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Biome cached = biomes.get(name);
        if (cached != null) {
            return cached;
        }
        try {
            Biome biome = Registry.BIOME.get(NamespacedKey.minecraft(name));
            if (biome != null) {
                biomes.put(name, biome);
            }
            return biome;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    int layers() {
        return (maxY >> 2) - (minY >> 2) + 1;
    }

    int minQuartY() {
        return minY >> 2;
    }

    int maxQuartY() {
        return maxY >> 2;
    }

    int cellsPerChunk() {
        return layers() * 16;
    }

    /**
     * Что ставить клетке с данным исходным биомом в этот сезон.
     * Ключи — все биомы, которые вообще участвуют в цикле (объединение таблиц).
     * Весна и restore возвращают исходный биом; биом вне таблиц не меняется никогда.
     */
    Map<String, String> goals(Season season) {
        Set<String> changeable = new HashSet<>(spring.keySet());
        changeable.addAll(summer.keySet());
        changeable.addAll(autumn.keySet());
        changeable.addAll(winter.keySet());
        Map<String, String> goals = new HashMap<>();
        for (String name : changeable) {
            String goal;
            if (season == Season.SPRING || season == Season.RESTORE) {
                goal = name;
            } else {
                goal = switch (season) {
                    case SUMMER -> summer.get(name);
                    case AUTUMN -> autumn.get(name);
                    case WINTER -> winter.get(name);
                    default -> null;
                };
            }
            if (goal == null || biome(goal) == null) {
                continue;
            }
            goals.put(name, goal);
        }
        return goals;
    }

    World overworld(CommandSender sender) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                return world;
            }
        }
        tell(sender, "[Seasons] нет мира overworld");
        return null;
    }

    // ---------- команды ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("seasons.admin")) {
            tell(sender, "[Seasons] нужно право seasons.admin");
            return true;
        }
        if (store == null) {
            tell(sender, "[Seasons] " + (storeError == null ? "карта недоступна" : storeError));
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "stop" -> {
                if (pass == null) {
                    tell(sender, "[Seasons] ничего не выполняется");
                } else {
                    pass.stop();
                    pass = null;
                    tell(sender, "[Seasons] проход остановлен, прогресс сохранён");
                }
            }
            case "scan" -> scanCommand(sender, args);
            case "spring", "summer", "autumn", "winter", "restore" ->
                    applyCommand(sender, Season.parse(sub), args);
            default -> tell(sender, "[Seasons] /seasons <scan|spring|summer|autumn|winter|restore|status|stop>");
        }
        return true;
    }

    private void scanCommand(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "start" -> {
                if (pass != null && !pass.isFinished()) {
                    tell(sender, "[Seasons] уже выполняется " + pass.label() + " (" + pass.progress() + ")");
                    return;
                }
                int radius = args.length >= 3 ? parseInt(sender, args[2]) : scanMaxRadius;
                if (radius < 0) {
                    return;
                }
                startScan(radius);
                tell(sender, "[Seasons] сканирование запущено: от центра мира наружу,"
                        + " ограничение " + (radius <= 0 ? "нет" : radius + " блоков"));
            }
            case "stop" -> {
                if (pass == null) {
                    tell(sender, "[Seasons] сканирование не идёт");
                } else {
                    pass.stop();
                    pass = null;
                    tell(sender, "[Seasons] сканирование остановлено, прогресс сохранён");
                }
            }
            default -> status(sender);
        }
    }


    /** Сезонная скорость случайных тиков: зимой 0 (снег только копится), летом быстрое таяние. */
    private void applyRandomTickSpeed(World world, Season season) {
        String state = season.state.toLowerCase(java.util.Locale.ROOT);
        String key = "random-tick-speed." + state;
        if (!getConfig().isInt(key)) {
            return;
        }
        int value = getConfig().getInt(key);
        try {
            org.bukkit.GameRule<Integer> rule = org.bukkit.GameRule.RANDOM_TICK_SPEED;
            Integer before = world.getGameRuleValue(rule);
            world.setGameRule(rule, value);
            getLogger().info("randomTickSpeed: " + before + " -> " + world.getGameRuleValue(rule) + " (" + state + ")");
        } catch (Exception e) {
            getLogger().warning("не удалось поставить randomTickSpeed: " + e);
        }
    }

    private void applyCommand(CommandSender sender, Season season, String[] args) {
        if (season == null) {
            tell(sender, "[Seasons] неизвестный сезон");
            return;
        }
        if (pass != null && !pass.isFinished()) {
            tell(sender, "[Seasons] уже выполняется " + pass.label() + " (" + pass.progress() + ")");
            return;
        }
        int radius = args.length >= 2 ? parseInt(sender, args[1]) : 0;
        if (radius < 0) {
            return;
        }
        World world = overworld(sender);
        if (world == null) {
            return;
        }
        applyRandomTickSpeed(world, season);
        Applier applier = new Applier(this, world, store, season, chunksPerTick, millisPerTick, radius, commitEvery);
        if (applier.planned() == 0) {
            tell(sender, "[Seasons] " + season.state
                    + ": менять нечего (карта пуста, всё уже в этом состоянии или радиус слишком мал)");
            return;
        }
        pass = applier;
        applier.start();
        tell(sender, "[Seasons] " + season.state + ": чанков в работе " + applier.planned()
                + (radius > 0 ? ", радиус " + radius + " блоков" : ", вся карта"));
    }

    private int parseInt(CommandSender sender, String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            tell(sender, "[Seasons] радиус должен быть числом: " + text);
            return -1;
        }
    }

    private void startScan(int radiusBlocks) {
        World world = overworld(Bukkit.getConsoleSender());
        if (world == null) {
            return;
        }
        Scanner scanner = new Scanner(this, world, store, chunksPerTick, millisPerTick,
                radiusBlocks, commitEvery, logEvery);
        pass = scanner;
        scanner.start();
    }

    void passFinished(Pass finishedPass, String summary) {
        lastOp = finishedPass.label() + ": " + summary + " (" + LocalDateTime.now().format(TIME) + ")";
        if (finishedPass == pass) {
            pass = null;
        }
        if ("scan".equals(finishedPass.label())) {
            scanState = "завершено: " + lastOp;
        }
    }

    // ---------- status ----------

    private void status(CommandSender sender) {
        World world = overworld(sender);
        Map<String, long[]> totals = world == null ? Map.of() : store.totals(world.getName());
        long[] sum = new long[7];
        List<String> byState = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : totals.entrySet()) {
            byState.add(entry.getKey() + "=" + entry.getValue()[0]);
            for (int i = 0; i < sum.length; i++) {
                sum[i] += entry.getValue()[i];
            }
        }
        tell(sender, "[Seasons] карта: " + (world == null ? "?" : world.getName()) + " — " + sum[0]
                + " чанков, " + (sum[1] + sum[2] + sum[3] + sum[4] + sum[5]) + " клеток, "
                + String.format(Locale.ROOT, "%.0f", store.fileSize() / 1048576.0) + " МБ");
        tell(sender, "[Seasons] клетки по сезонам: spring " + sum[1] + ", summer " + sum[2]
                + ", autumn " + sum[3] + ", winter " + sum[4] + ", original " + sum[5]
                + "; переписано последней операцией " + sum[6]);
        tell(sender, "[Seasons] чанки по состоянию: " + String.join(", ", byState));
        String scanRing = store.meta("scan_ring");
        boolean complete = "1".equals(store.meta("scan_complete"));
        tell(sender, "[Seasons] сканирование: " + (complete ? "карта пройдена до конца"
                : scanState + (scanRing == null ? "" : ", кольцо " + scanRing)));
        if (pass != null && !pass.isFinished()) {
            tell(sender, "[Seasons] сейчас идёт " + pass.label() + ": " + pass.progress());
        }
        tell(sender, "[Seasons] последняя операция: " + lastOp);
    }

    private static void tell(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("seasons.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("scan")) {
            return filter(List.of("start", "stop", "status"), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}

package seasonsbar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import tr.alperendemir.seasons.api.TemperatureAPI;

/** Полоска температуры из SeasonsPlus: без цифр, свой боссбар на игрока. */
public final class SeasonsBar extends JavaPlugin implements Listener {

    private record Threshold(double max, BarColor color) {}

    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final TemperatureAPI api = new TemperatureAPI();
    private final List<Threshold> thresholds = new ArrayList<>();
    private double min;
    private double max;
    private String title;
    private boolean debug;
    private long debugMillis;
    private long lastDebugAt;
    private boolean warned;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        min = getConfig().getDouble("min-temperature", -20);
        max = getConfig().getDouble("max-temperature", 60);
        if (max <= min) {
            getLogger().warning("max-temperature <= min-temperature, беру max = min + 1");
            max = min + 1;
        }
        title = getConfig().getString("title", "температура");
        debug = getConfig().getBoolean("debug", false);
        debugMillis = getConfig().getLong("debug-seconds", 10) * 1000L;

        for (Map<?, ?> row : getConfig().getMapList("thresholds")) {
            if (!(row.get("max") instanceof Number value) || row.get("color") == null) {
                continue;
            }
            try {
                thresholds.add(new Threshold(
                        value.doubleValue(),
                        BarColor.valueOf(row.get("color").toString().toUpperCase(Locale.ROOT))));
            } catch (IllegalArgumentException e) {
                getLogger().warning("thresholds: неизвестный цвет " + row.get("color"));
            }
        }
        thresholds.sort(Comparator.comparingDouble(Threshold::max));
        if (thresholds.isEmpty()) {
            getLogger().warning("thresholds пуст — полоска будет белой");
            thresholds.add(new Threshold(Double.MAX_VALUE, BarColor.WHITE));
        }

        long period = Math.max(1, getConfig().getLong("interval-ticks", 20));
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            bar(player);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::update, period, period);
    }

    @Override
    public void onDisable() {
        bars.values().forEach(BossBar::removeAll);
        bars.clear();
    }

    private void update() {
        long now = System.currentTimeMillis();
        boolean log = debug && now - lastDebugAt >= debugMillis;
        for (Player player : Bukkit.getOnlinePlayers()) {
            BossBar bar = bar(player);
            double temperature;
            try {
                temperature = api.getPlayerTemperature(player);
            } catch (RuntimeException e) {
                warn("не удалось прочитать температуру: " + e);
                continue;
            }
            if (Double.isNaN(temperature)) {
                warn("SeasonsPlus вернул пустую температуру — оставляю прежнее значение полоски");
                continue;
            }
            bar.setProgress(progress(temperature));
            bar.setColor(color(temperature));
            if (log) {
                getLogger().info("%s temp=%.1f → value=%.2f,color=%s"
                        .formatted(player.getName(), temperature, progress(temperature), color(temperature)));
            }
        }
        if (log) {
            lastDebugAt = now;
        }
    }

    private BossBar bar(Player player) {
        return bars.computeIfAbsent(player.getUniqueId(), id -> {
            BossBar created = Bukkit.createBossBar(title, BarColor.WHITE, BarStyle.SEGMENTED_12);
            created.setProgress(0.5);
            created.addPlayer(player);
            return created;
        });
    }

    private double progress(double temperature) {
        return Math.max(0, Math.min(1, (temperature - min) / (max - min)));
    }

    private BarColor color(double temperature) {
        for (Threshold threshold : thresholds) {
            if (temperature <= threshold.max()) {
                return threshold.color();
            }
        }
        return thresholds.getLast().color();
    }

    private void warn(String message) {
        if (!warned) {
            warned = true;
            getLogger().warning(message + " (дальнейшие предупреждения подавлены)");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        bar(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        BossBar bar = bars.remove(event.getPlayer().getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
    }
}

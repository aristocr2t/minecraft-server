package ru.geekideas.mc.thermo;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ThermoPlugin extends JavaPlugin implements Listener, TabExecutor {

    private record State(String id, double max, String label, double damage, int damageSeconds, List<String> effects) {
    }

    private final Map<UUID, Double> body = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Integer> outsideTicks = new HashMap<>();
    private final Map<UUID, Integer> illnessTicks = new HashMap<>();
    private final Map<UUID, Integer> damageClock = new HashMap<>();

    private final List<State> states = new ArrayList<>();
    private NamespacedKey thermometerKey;

    private File seasonFile;
    private String season = "WINTER";
    private int seasonElapsed;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        thermometerKey = new NamespacedKey(this, "thermometer");
        loadStates();
        loadSeason();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("thermo") != null) {
            getCommand("thermo").setExecutor(this);
        }
        int interval = Math.max(5, getConfig().getInt("interval-ticks", 20));
        Bukkit.getScheduler().runTaskTimer(this, this::tick, interval, interval);
        Bukkit.getScheduler().runTaskTimer(this, this::seasonTick, 20L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) {
            join(p);
        }
        getLogger().info("Enabling Thermo " + getPluginMeta().getVersion() + " (saison " + season + ")");
    }

    @Override
    public void onDisable() {
        for (BossBar bar : bars.values()) {
            bar.removeAll();
        }
        bars.clear();
    }

    // ---------------------------------------------------------------- состояния

    private void loadStates() {
        states.clear();
        for (Map<?, ?> raw : getConfig().getMapList("states")) {
            String id = String.valueOf(raw.get("id"));
            double max = raw.get("max") instanceof Number n ? n.doubleValue() : 99;
            String label = raw.get("label") == null ? id : String.valueOf(raw.get("label"));
            double damage = raw.get("damage") instanceof Number n ? n.doubleValue() : 0;
            int seconds = raw.get("damage-seconds") instanceof Number n ? n.intValue() : 0;
            List<String> effects = new ArrayList<>();
            if (raw.get("effects") instanceof List<?> list) {
                for (Object e : list) {
                    if (e instanceof Map<?, ?> m && m.get("type") != null) {
                        String type = String.valueOf(m.get("type"));
                        int amp = m.get("amplifier") instanceof Number a ? a.intValue() : 0;
                        effects.add(type + ":" + amp);
                    }
                }
            }
            states.add(new State(id, max, label, damage, seconds, effects));
        }
        if (states.isEmpty()) {
            states.add(new State("normal", 99, "норма", 0, 0, List.of()));
        }
    }

    private State stateOf(double temperature) {
        for (State s : states) {
            if (temperature <= s.max()) {
                return s;
            }
        }
        return states.get(states.size() - 1);
    }

    private PotionEffectType effectType(String name) {
        return switch (name) {
            case "SLOWNESS" -> PotionEffectType.SLOWNESS;
            case "NAUSEA" -> PotionEffectType.NAUSEA;
            case "HUNGER" -> PotionEffectType.HUNGER;
            case "WEAKNESS" -> PotionEffectType.WEAKNESS;
            case "MINING_FATIGUE" -> PotionEffectType.MINING_FATIGUE;
            case "SPEED" -> PotionEffectType.SPEED;
            default -> null;
        };
    }

    private void applyEffects(Player player, List<String> effects) {
        for (String entry : effects) {
            String[] parts = entry.split(":");
            PotionEffectType type = effectType(parts[0]);
            if (type == null) {
                continue;
            }
            int amp = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            player.addPotionEffect(new PotionEffect(type, 60, amp, true, false, true));
        }
    }

    // ---------------------------------------------------------------- тик температуры

    private void tick() {
        FileConfiguration cfg = getConfig();
        double base = cfg.getDouble("body-base", 36.6);
        double factor = cfg.getDouble("body-factor", 0.22);

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Env.Result env = Env.of(player, cfg, season);
            boolean wet = player.isInWater();
            double clothing = Env.clothing(player, cfg, wet);

            double delta = (env.temperature() - 20) * factor + clothing;
            int food = player.getFoodLevel();
            if (food >= 18) {
                delta += cfg.getDouble("factors.well-fed", 0.4);
            } else if (food <= 6) {
                delta += cfg.getDouble("factors.hungry", -0.6);
            }
            if (player.isSprinting()) {
                delta += cfg.getDouble("factors.sprinting", 1.0);
            }
            if (wet) {
                delta += cfg.getDouble("factors.wet", -1.0);
            }

            double target = Math.max(25.0, Math.min(45.0, base + delta));
            double step = cfg.getDouble("body-drift-per-second", 0.05)
                    * Math.max(1, cfg.getInt("interval-ticks", 20)) / 20.0;

            // сон и лежание на кровати тянут тело обратно к норме
            if (player.isSleeping()) {
                target = base;
                step *= cfg.getDouble("rest.sleep-multiplier", 4.0);
            } else if (player.isSneaking() && isOnBed(player)) {
                target = base;
                step *= cfg.getDouble("rest.bed-multiplier", 2.0);
            }

            double current = body.getOrDefault(id, target);
            double value = current + Math.max(-step, Math.min(step, target - current));
            body.put(id, value);

            State state = stateOf(value);
            applyEffects(player, state.effects());
            applyDamage(player, state);
            applyIllness(player, state);
            updateBar(player, value, state, cfg);
            updateActionBar(player, value, state, env, clothing, cfg);
        }
    }

    /** Игрок стоит/лежит на кровати: блок под ним или в нём — кровать. */
    private boolean isOnBed(Player player) {
        var loc = player.getLocation();
        return isBed(loc.getBlock().getType()) || isBed(loc.clone().subtract(0, 1, 0).getBlock().getType());
    }

    private boolean isBed(Material material) {
        return material.name().endsWith("_bed");
    }

    private void applyDamage(Player player, State state) {
        if (state.damage() <= 0 || state.damageSeconds() <= 0) {
            return;
        }
        UUID id = player.getUniqueId();
        int clock = damageClock.merge(id, 1, Integer::sum);
        if (clock % (state.damageSeconds() * 20) == 0) {
            player.damage(state.damage());
        }
    }

    private void applyIllness(Player player, State state) {
        ConfigurationSection cfg = getConfig().getConfigurationSection("illness");
        if (cfg == null || !cfg.getBoolean("enabled", true)) {
            return;
        }
        UUID id = player.getUniqueId();
        double value = body.getOrDefault(id, 36.6);
        boolean outside = value < 34.0 || value > 40.0;
        int limit = Math.max(1, cfg.getInt("seconds-outside", 60)) * 20;

        if (!outside) {
            outsideTicks.remove(id);
            return;
        }
        int ticks = outsideTicks.merge(id, getConfig().getInt("interval-ticks", 20), Integer::sum);
        if (ticks < limit) {
            return;
        }
        outsideTicks.remove(id);
        int cooldown = illnessTicks.getOrDefault(id, 0);
        if (cooldown > 0) {
            illnessTicks.put(id, cooldown - 1);
            return;
        }
        illnessTicks.put(id, cfg.getInt("duration", 15));
        int duration = Math.max(20, cfg.getInt("duration", 15) * 20);
        for (String entry : List.of("NAUSEA:0", "WEAKNESS:0", "HUNGER:0")) {
            String[] parts = entry.split(":");
            PotionEffectType type = effectType(parts[0]);
            if (type != null) {
                player.addPotionEffect(new PotionEffect(type, duration, Integer.parseInt(parts[1]), true, false, true));
            }
        }
        player.sendActionBar(Component.text("вы заболели: отдохните, поешьте, попейте воды"));
    }

    private void updateBar(Player player, double value, State state, FileConfiguration cfg) {
        if (!cfg.getBoolean("bossbar.enabled", true)) {
            return;
        }
        BossBar bar = bars.computeIfAbsent(player.getUniqueId(), k -> {
            BossBar created = Bukkit.createBossBar("", BarColor.GREEN, barStyle(cfg));
            created.addPlayer(player);
            return created;
        });
        double min = cfg.getDouble("bossbar.min", 30.0);
        double max = cfg.getDouble("bossbar.max", 43.0);
        double progress = max > min ? (value - min) / (max - min) : 0;
        bar.setProgress(Math.max(0, Math.min(1, progress)));
        bar.setTitle(cfg.getString("bossbar.title", "температура {temp}°C")
                .replace("{temp}", one(value))
                .replace("{state}", state.label()));
        bar.setColor(barColor(cfg.getString("colors." + state.id(), "GREEN")));
    }

    private void updateActionBar(Player player, double value, State state, Env.Result env,
                                 double clothing, FileConfiguration cfg) {
        if (!cfg.getBoolean("thermometer.actionbar", true) || !holdingThermometer(player)) {
            return;
        }
        int interval = Math.max(10, cfg.getInt("thermometer.actionbar-interval-ticks", 40));
        UUID id = player.getUniqueId();
        int clock = damageClock.getOrDefault(id, 0);
        if (clock % interval != 0) {
            return;
        }
        player.sendActionBar(Component.text(
                "Среда: " + one(env.temperature()) + "°C · Тело: " + one(value) + "°C (" + state.label() + ")"));
        if (clothing != 0) {
            // одежда уже учтена в теле; в actionbar не дублируем
        }
    }

    // ---------------------------------------------------------------- боссбар

    private BarStyle barStyle(FileConfiguration cfg) {
        return switch (cfg.getString("bossbar.style", "notched_12")) {
            case "notched_6" -> BarStyle.SEGMENTED_6;
            case "notched_10" -> BarStyle.SEGMENTED_10;
            case "notched_20" -> BarStyle.SEGMENTED_20;
            case "progress" -> BarStyle.SOLID;
            default -> BarStyle.SEGMENTED_12;
        };
    }

    private BarColor barColor(String name) {
        try {
            return BarColor.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BarColor.WHITE;
        }
    }

    // ---------------------------------------------------------------- сезоны

    private void loadSeason() {
        seasonFile = new File(getDataFolder(), "season.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(seasonFile);
        season = yml.getString("season", getConfig().getString("seasons.start", "WINTER")).toUpperCase(Locale.ROOT);
        seasonElapsed = yml.getInt("elapsed-seconds", 0);
    }

    private void saveSeason() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("season", season);
        yml.set("elapsed-seconds", seasonElapsed);
        try {
            yml.save(seasonFile);
        } catch (Exception e) {
            getLogger().warning("Не удалось сохранить сезон: " + e.getMessage());
        }
    }

    private void seasonTick() {
        seasonElapsed++;
        long duration = Math.max(1, getConfig().getInt("seasons.duration-minutes", 1440)) * 60L;
        if (seasonElapsed < duration) {
            return;
        }
        seasonElapsed = 0;
        setSeason(nextSeason());
    }

    private String nextSeason() {
        List<String> order = getConfig().getStringList("seasons.order");
        if (order.isEmpty()) {
            return "WINTER";
        }
        int index = order.indexOf(season);
        return order.get((index + 1) % order.size()).toUpperCase(Locale.ROOT);
    }

    private void setSeason(String newSeason) {
        season = newSeason.toUpperCase(Locale.ROOT);
        seasonElapsed = 0;
        saveSeason();
        getLogger().info("Сезон сменился на " + season);
        if (getConfig().getBoolean("seasons.call-seasons-plugin", true)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "seasons " + season.toLowerCase(Locale.ROOT));
        }
    }

    // ---------------------------------------------------------------- градусник

    private ItemStack thermometer() {
        ConfigurationSection cfg = getConfig().getConfigurationSection("thermometer");
        Material material = Material.matchMaterial(cfg == null ? "CLOCK" : cfg.getString("material", "CLOCK"));
        ItemStack item = new ItemStack(material == null ? Material.CLOCK : material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(cfg == null ? "Градусник" : cfg.getString("name", "Градусник")));
        if (cfg != null && cfg.getBoolean("glint", true)) {
            Enchantment glint = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
            if (glint != null) {
                meta.addEnchant(glint, 1, true);
            }
        }
        if (cfg != null && cfg.getInt("custom-model-data", 0) > 0) {
            meta.setCustomModelData(cfg.getInt("custom-model-data"));
        }
        meta.getPersistentDataContainer().set(thermometerKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isThermometer(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(thermometerKey, PersistentDataType.BYTE);
    }

    private boolean holdingThermometer(Player player) {
        return isThermometer(player.getInventory().getItemInMainHand())
                || isThermometer(player.getInventory().getItemInOffHand());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!isThermometer(event.getItem())) {
            return;
        }
        if (!event.getAction().isRightClick()) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Env.Result env = Env.of(player, getConfig(), season);
        double value = body.getOrDefault(player.getUniqueId(), getConfig().getDouble("body-base", 36.6));
        State state = stateOf(value);
        double clothing = Env.clothing(player, getConfig(), player.isInWater());
        FileConfiguration cfg = getConfig();
        player.sendMessage(Component.text(cfg.getString("messages.env-line", "Среда: {env}°C ({env-state})")
                .replace("{env}", one(env.temperature()))
                .replace("{env-state}", envState(env.temperature()))));
        player.sendMessage(Component.text(cfg.getString("messages.body-line", "Тело: {body}°C ({body-state})")
                .replace("{body}", one(value))
                .replace("{body-state}", state.label())));
        player.sendMessage(Component.text(cfg.getString("messages.clothes-line", "Одежда: {bonus}")
                .replace("{bonus}", (clothing >= 0 ? "+" : "") + one(clothing))));
        player.sendMessage(Component.text(cfg.getString("messages.reason-line", "Причина среды: {reason}")
                .replace("{reason}", env.reason())));
    }

    private String envState(double env) {
        if (env <= -10) {
            return "морозно";
        }
        if (env <= 5) {
            return "холодно";
        }
        if (env <= 25) {
            return "умеренно";
        }
        if (env <= 35) {
            return "тепло";
        }
        return "жарко";
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        join(event.getPlayer());
    }

    private void join(Player player) {
        body.put(player.getUniqueId(), getConfig().getDouble("body-base", 36.6));
        if (getConfig().getBoolean("bossbar.enabled", true)) {
            BossBar bar = Bukkit.createBossBar("", BarColor.GREEN, barStyle(getConfig()));
            bar.addPlayer(player);
            bars.put(player.getUniqueId(), bar);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        BossBar bar = bars.remove(event.getPlayer().getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
        body.remove(event.getPlayer().getUniqueId());
        outsideTicks.remove(event.getPlayer().getUniqueId());
        damageClock.remove(event.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- команды

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("использование: /thermo status|reload|season|set|thermometer"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender, args);
            case "reload" -> {
                if (!sender.hasPermission("thermo.admin")) {
                    sender.sendMessage(Component.text(getConfig().getString("messages.no-permission", "нет прав")));
                    return true;
                }
                reloadConfig();
                loadStates();
                loadSeason();
                sender.sendMessage(Component.text("конфиг перечитан, сезон " + season));
            }
            case "season" -> {
                if (!sender.hasPermission("thermo.admin")) {
                    sender.sendMessage(Component.text(getConfig().getString("messages.no-permission", "нет прав")));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("сезон: " + season + " (осталось " + remainMinutes() + " мин)"));
                    return true;
                }
                setSeason(args[1]);
                sender.sendMessage(Component.text("сезон установлен: " + season));
            }
            case "set" -> {
                if (!sender.hasPermission("thermo.admin") || !(sender instanceof Player player) || args.length < 2) {
                    sender.sendMessage(Component.text("использование: /thermo set <температура> (в игре)"));
                    return true;
                }
                try {
                    double value = Double.parseDouble(args[1]);
                    body.put(player.getUniqueId(), value);
                    player.sendMessage(Component.text("температура тела: " + one(value) + "°C (" + stateOf(value).label() + ")"));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("не число: " + args[1]));
                }
            }
            case "thermometer" -> {
                if (!sender.hasPermission("thermo.admin")) {
                    sender.sendMessage(Component.text(getConfig().getString("messages.no-permission", "нет прав")));
                    return true;
                }
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1])
                        : (sender instanceof Player p ? p : null);
                if (target == null) {
                    sender.sendMessage(Component.text("игрок не найден"));
                    return true;
                }
                target.getInventory().addItem(thermometer());
                sender.sendMessage(Component.text("градусник выдан: " + target.getName()));
            }
            default -> sender.sendMessage(Component.text("неизвестная подкоманда: " + args[0]));
        }
        return true;
    }

    private void status(CommandSender sender, String[] args) {
        Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1])
                : (sender instanceof Player p ? p : null);
        if (target == null) {
            sender.sendMessage(Component.text("status доступен только игроку или указывай ник"));
            return;
        }
        Env.Result env = Env.of(target, getConfig(), season);
        double value = body.getOrDefault(target.getUniqueId(), 36.6);
        State state = stateOf(value);
        double clothing = Env.clothing(target, getConfig(), target.isInWater());
        sender.sendMessage(Component.text(target.getName() + ": body=" + one(value) + "°C state=" + state.id()
                + " (" + state.label() + ") env=" + one(env.temperature()) + "°C reason=" + env.reason()
                + " clothes=" + (clothing >= 0 ? "+" : "") + one(clothing)
                + " season=" + season + " осталось_мин=" + remainMinutes()));
    }

    private long remainMinutes() {
        long duration = Math.max(1, getConfig().getInt("seasons.duration-minutes", 1440)) * 60L;
        return (duration - seasonElapsed) / 60;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "reload", "season", "set", "thermometer").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("season")) {
            return List.of("winter", "spring", "summer", "autumn").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }

    private static String one(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}

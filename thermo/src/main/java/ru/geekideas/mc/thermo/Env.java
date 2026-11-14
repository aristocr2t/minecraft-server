package ru.geekideas.mc.thermo;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/** Расчёт температуры окружающей среды и бонусов одежды. */
public final class Env {

    private Env() {
    }

    public record Result(double temperature, String reason) {
    }

    public static Result of(Player player, FileConfiguration cfg, String season) {
        double t = cfg.getDouble("environment.base", 20.0);
        StringBuilder why = new StringBuilder();

        World world = player.getWorld();
        String biomeKey = world.getBiome(player.getLocation()).getKey().getKey();

        String biomeName = null;
        double biomeDelta = 0;
        ConfigurationSection biomes = cfg.getConfigurationSection("environment.biomes");
        if (biomes != null) {
            for (String key : biomes.getKeys(false)) {
                if (biomeKey.contains(key)) {
                    biomeName = key;
                    biomeDelta = biomes.getDouble(key);
                    break;
                }
            }
        }
        t += biomeDelta;
        if (biomeName != null) {
            why.append("биом ").append(biomeName);
        }

        double heightStart = cfg.getDouble("environment.height-start", 100);
        double heightPer = cfg.getDouble("environment.height-per", 30);
        double heightStep = cfg.getDouble("environment.height-step", -1);
        double y = player.getLocation().getY();
        if (heightPer > 0 && y > heightStart) {
            t += Math.floor((y - heightStart) / heightPer) * heightStep;
            append(why, "высота");
        }

        double seasonDelta = cfg.getDouble("environment.seasons." + season, 0);
        t += seasonDelta;
        if (seasonDelta != 0) {
            append(why, "сезон " + season.toLowerCase());
        }

        if (world.hasStorm() && world.getHighestBlockYAt(player.getLocation()) <= player.getLocation().getBlockY()) {
            double weather = world.isThundering()
                    ? cfg.getDouble("environment.weather.thunder", -6)
                    : cfg.getDouble("environment.weather.rain", -4);
            t += weather * (1 - weatherResist(player, cfg));
            append(why, world.isThundering() ? "гроза" : "дождь");
        }

        if (player.isInWater()) {
            boolean snowy = biomeName != null && (biomeName.contains("snowy") || biomeName.contains("frozen"));
            t += snowy
                    ? cfg.getDouble("environment.water-snowy", -12)
                    : cfg.getDouble("environment.water", -8);
            append(why, "вода");
        }

        for (String dim : new String[]{"world_the_nether", "world_the_end"}) {
            if (world.getName().endsWith(dim)) {
                double d = cfg.getDouble("environment.dimensions." + dim, 0);
                if (d != 0) {
                    t += d;
                    append(why, dim.contains("nether") ? "ад" : "энд");
                }
            }
        }

        // блок под ногами: лёд и снег охлаждают
        ConfigurationSection surface = cfg.getConfigurationSection("environment.surface");
        if (surface != null) {
            Material below = player.getLocation().clone().subtract(0, 0.2, 0).getBlock().getType();
            if (surface.contains(below.name())) {
                t += surface.getDouble(below.name());
                append(why, "под ногами " + below.name().toLowerCase(java.util.Locale.ROOT));
            }
        }

        int radius = cfg.getInt("environment.heat-radius", 4);
        ConfigurationSection heat = cfg.getConfigurationSection("environment.heat");
        if (heat != null && radius > 0) {
            Set<Material> found = EnumSet.noneOf(Material.class);
            Block origin = player.getLocation().getBlock();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        Material m = origin.getRelative(dx, dy, dz).getType();
                        if (found.contains(m)) {
                            continue;
                        }
                        if (heat.contains(m.name())) {
                            found.add(m);
                            t += heat.getDouble(m.name());
                        }
                    }
                }
            }
            if (!found.isEmpty()) {
                append(why, "тепло рядом");
            }
        }

        return new Result(t, why.length() == 0 ? "нейтрально" : why.toString());
    }

    /** Доля гашения погоды бронёй (0..0.75). */
    public static double weatherResist(Player player, FileConfiguration cfg) {
        ConfigurationSection section = cfg.getConfigurationSection("weather-resist");
        if (section == null) {
            return 0;
        }
        double sum = 0;
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null) {
                sum += section.getDouble(item.getType().name(), 0);
            }
        }
        return Math.min(0.75, sum);
    }

    /** Охлаждение от брони (кольчуга): сдвигает тело вниз — в жару помогает, в холод вредит. */
    public static double cooling(Player player, FileConfiguration cfg) {
        ConfigurationSection section = cfg.getConfigurationSection("cooling");
        if (section == null) {
            return 0;
        }
        double sum = 0;
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null) {
                sum += section.getDouble(item.getType().name(), 0);
            }
        }
        return sum;
    }

    /** Суммарный бонус одежды; мокрый игрок получает половину. */
    public static double clothing(Player player, FileConfiguration cfg, boolean wet) {
        ConfigurationSection section = cfg.getConfigurationSection("clothing");
        if (section == null) {
            return 0;
        }
        double sum = 0;
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null) {
                sum += section.getDouble(item.getType().name(), 0);
            }
        }
        return wet ? sum / 2 : sum;
    }

    private static void append(StringBuilder sb, String part) {
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(part);
    }
}

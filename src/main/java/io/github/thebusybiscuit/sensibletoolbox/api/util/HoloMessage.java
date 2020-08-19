package io.github.thebusybiscuit.sensibletoolbox.api.util;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.gmail.filoghost.holographicdisplays.api.Hologram;
import com.gmail.filoghost.holographicdisplays.api.HologramsAPI;

import io.github.thebusybiscuit.sensibletoolbox.SensibleToolboxPlugin;
import me.desht.dhutils.MiscUtil;

public class HoloMessage {

    public static void popup(Player player, Location loc, String... message) {
        if (!SensibleToolboxPlugin.getInstance().isHolographicDisplaysEnabled() || !SensibleToolboxPlugin.getInstance().getConfig().getBoolean("holograms.enabled")) {
            for (String line : message) {
                MiscUtil.statusMessage(player, line);
            }
            return;
        }

        Vector v = player.getLocation().getDirection();
        v.setY(0).multiply(-0.8).add(new Vector(0.5, 0.8, 0.5));

        Hologram h = HologramsAPI.createHologram(SensibleToolboxPlugin.getInstance(), loc.add(v));

        SensibleToolboxPlugin.getInstance().getServer().getScheduler().scheduleSyncDelayedTask(SensibleToolboxPlugin.getInstance(), () -> h.delete(), SensibleToolboxPlugin.getInstance().getConfig().getInt("holograms.duration-in-seconds"));
    }
}

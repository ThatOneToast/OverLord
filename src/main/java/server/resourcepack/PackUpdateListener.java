package server.resourcepack;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import server.GlobalEventHandler;
import server.OverLord;

import java.util.List;
import java.util.Optional;

public class PackUpdateListener {

    public static void register() {
        final GlobalEventHandler eventHandler = GlobalEventHandler.get();
        PackManager packManager = OverLord.getPackManager();

        eventHandler.addListener(PlayerQuitEvent.class, event -> packManager.clearEnabledPacksCache(event.getPlayer()));

        eventHandler.addListener(PlayerJoinEvent.class, event -> {
            Player player = event.getPlayer();
            List<Resourcepack> autoPacks = packManager.getAutoResourcePacks();
            autoPacks.forEach(resourcepack -> packManager.sendPack(player, resourcepack.getPackFile().getName(), true, Optional.empty()));
        });
    }
}
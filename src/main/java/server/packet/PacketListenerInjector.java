package server.packet;

import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import server.GlobalEventHandler;

public class PacketListenerInjector {
    private static final GlobalEventHandler eventHandler = GlobalEventHandler.get();

    public static void register() {
        eventHandler.addListener(PlayerJoinEvent.class, event-> PacketUtils.injectPlayer(event.getPlayer()));
        eventHandler.addListener(PlayerQuitEvent.class, event-> PacketUtils.removePlayer(event.getPlayer()));
    }
}

package server.packet;

import lombok.Getter;
import net.minecraft.network.protocol.Packet;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PacketManager {
    private final List<AbstractPacketListener> listeners = new CopyOnWriteArrayList<>();

    @Getter private static final PacketManager instance = new PacketManager();

    public void registerListener(AbstractPacketListener listener) {
        listeners.add(listener);
    }

    public void unregisterListener(AbstractPacketListener listener) {
        listeners.remove(listener);
    }

    public boolean notifyServerboundPacket(Player player, Packet<?> packet) {
        for (AbstractPacketListener listener : listeners) {
            listener.onServerboundPacket(player, packet);
            if (listener.isCancelled()) {
                return true;
            }
        }
        return false;
    }

    public boolean notifyClientboundPacket(Player player, Packet<?> packet) {
        for (AbstractPacketListener listener : listeners) {
            listener.onClientboundPacket(player, packet);
            if (listener.isCancelled()) {
                return true;
            }
        }
        return false;
    }
}


package server.packet;

import lombok.Getter;
import lombok.Setter;
import net.minecraft.network.protocol.Packet;
import org.bukkit.entity.Player;

/**
 * Abstract class for listening to Minecraft packets.
 * Subclasses should override the methods to handle specific packets and use the cancellation mechanism.
 */
@Getter
@Setter
public abstract class AbstractPacketListener {

    private boolean cancelled = false;

    /**
     * Called when a serverbound packet is received.
     *
     * @param player the player sending the packet
     * @param packet the packet being sent to the server
     */
    public abstract void onServerboundPacket(Player player, Packet<?> packet);

    /**
     * Called when a clientbound packet is received.
     *
     * @param player the player receiving the packet
     * @param packet the packet being sent to the client
     */
    public abstract void onClientboundPacket(Player player, Packet<?> packet);
}

package server.resourcepack;

import lombok.Getter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import server.OverLord;

import java.io.File;
import java.util.Optional;
import java.util.UUID;

@Getter
public class Resourcepack {
    private final UUID id;
    private final String url;
    private final String hash;

    private final File packFile;

    @Getter private static final String globalIp = PackUtils.getIp();

    public Resourcepack(File packFile) {
        if(!packFile.exists()
                || !packFile.getName().endsWith(".zip")
                || packFile.isDirectory()) throw new IllegalArgumentException("Illegal file detected: " + packFile);

        this.id = UUID.randomUUID();
        this.packFile = packFile;
        this.hash = PackUtils.calcSHA1String(packFile);
        this.url = String.format("http://%s:%d/%s", globalIp, PackManager.PORT, packFile.getName());
    }

    public void send(Player player, boolean required, Optional<String> prompt) {
        Bukkit.getScheduler().runTask(OverLord.getInstance(), () -> {
            ClientboundResourcePackPushPacket pushPacket = new ClientboundResourcePackPushPacket(
                    id,
                    url,
                    hash,
                    required,
                    prompt.map(Component::literal));

            ((CraftPlayer)player).getHandle().connection.connection.send(pushPacket);
        });
    }

    public void remove(Player player) {
        Bukkit.getScheduler().runTask(OverLord.getInstance(), () -> {
            ClientboundResourcePackPopPacket popPacket = new ClientboundResourcePackPopPacket(Optional.of(id));
            ((CraftPlayer) player).getHandle().connection.connection.send(popPacket);
        });
    }
}
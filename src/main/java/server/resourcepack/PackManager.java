package server.resourcepack;

import lombok.Getter;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import server.Config;
import server.OverLord;
import server.PluginLogger;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class PackManager {
    public static final Config CONFIG;
    public static final int PORT;
    static {
        CONFIG = new Config("resource-packs.yml");
        if (!CONFIG.contains("port")) {
            CONFIG.set("port", 8069);
            CONFIG.save();
        }
        PORT = CONFIG.get("port", Integer.class);
    }

    private final Map<String, Resourcepack> resourcePacks = new HashMap<>();
    private final List<Resourcepack> autoSendResourcePacks = new ArrayList<>();
    private final Map<UUID, List<String>> playerPacks = Collections.synchronizedMap(new HashMap<>());
    private final PluginLogger LOGGER = OverLord.getLog();
    private final File resourceDir;

    @Getter private HttpServer httpServer;
    private boolean saveScheduled = false;

    public PackManager(JavaPlugin plugin) {
        this.resourceDir = new File(plugin.getDataFolder() + File.separator + "resource-packs");
        if (!resourceDir.exists()) resourceDir.mkdirs();
        reloadResourcePacks();
        init();
    }

    private void scheduleAutosendSave() {
        if (saveScheduled) return;
        saveScheduled = true;
        Bukkit.getScheduler().runTaskLater(OverLord.getInstance(), () -> {
            CONFIG.set("autosendpacks",
                    autoSendResourcePacks.stream()
                            .map(p -> p.getPackFile().getName())
                            .toList()
            );
            CONFIG.save();
            saveScheduled = false;
        }, 100L); // delay 5s (100 ticks) to batch multiple changes
    }

    public boolean addAutoSendPack(Resourcepack resourcepack, boolean updatePlayers) {
        if (autoSendResourcePacks.contains(resourcepack)) return false;
        autoSendResourcePacks.add(resourcepack);
        scheduleAutosendSave();
        if (updatePlayers)
            Bukkit.getOnlinePlayers()
                    .forEach(p -> sendPack(p, resourcepack.getPackFile().getName(), true, Optional.empty()));
        return true;
    }

    public boolean removeAutoSendPack(Resourcepack resourcepack, boolean updatePlayers) {
        if (!autoSendResourcePacks.contains(resourcepack)) return false;
        autoSendResourcePacks.remove(resourcepack);
        scheduleAutosendSave();
        if (updatePlayers)
            Bukkit.getOnlinePlayers()
                    .forEach(p -> removePack(p, resourcepack.getPackFile().getName()));
        return true;
    }

    public List<Resourcepack> getAutoResourcePacks() {
        return new ArrayList<>(autoSendResourcePacks);
    }

    public Resourcepack addResourcePack(File packFile) {
        Resourcepack resourcepack = new Resourcepack(packFile);
        resourcePacks.put(packFile.getName(), resourcepack);
        return resourcepack;
    }

    public Resourcepack getResourcePack(String fileName) {
        return resourcePacks.get(fileName);
    }

    public synchronized boolean sendPack(Player player, String fileName, boolean required, Optional<String> prompt) {
        Resourcepack resourcepack = resourcePacks.get(fileName);
        if (resourcepack == null) {
            player.sendMessage("Resource pack not found: " + fileName);
            return false;
        }
        if (!resourcepack.getPackFile().exists()) {
            LOGGER.warn("Attempted to send pack \"{}\" to {} but it does not exist, Reloading packs...", fileName, player.getName());
            reloadResourcePacks();
            return false;
        }
        if (hasPackEnabled(player, fileName)) return false;

        resourcepack.send(player, required, prompt);

        List<String> packs = playerPacks.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        if (!packs.contains(fileName)) packs.add(fileName);

        return true;
    }

    public synchronized boolean removePack(Player player, String fileName) {
        Resourcepack resourcepack = resourcePacks.get(fileName);

        if (resourcepack != null) {
            if(!hasPackEnabled(player, fileName)) return false;
            resourcepack.remove(player);
            List<String> packs = playerPacks.get(player.getUniqueId());
            if (packs != null) {
                packs.remove(fileName);
                if (packs.isEmpty()) playerPacks.remove(player.getUniqueId());
            }
            return true;
        } else {
            player.sendMessage("Resource pack not found: " + fileName);
            return false;
        }
    }

    public boolean removeAllPacks(Player player) {
        try {
            ClientboundResourcePackPopPacket popPacket = new ClientboundResourcePackPopPacket(Optional.empty());
            ((CraftPlayer) player).getHandle().connection.connection.send(popPacket);

            List<String> packs = playerPacks.get(player.getUniqueId());
            if (packs != null) {
                clearEnabledPacksCache(player);
                if (packs.isEmpty()) playerPacks.remove(player.getUniqueId());
                return true;
            }
            return false;
        } catch (Exception e) {
            LOGGER.error("Failed to remove all packs for a player", e);
            LOGGER.warn("Failed to remove all packs for player {}", player.getName());
            return false;
        }
    }

    public boolean packExists(String fileName) {
        return resourcePacks.containsKey(fileName);
    }

    public Set<String> listResourcePacks() {
        return resourcePacks.keySet();
    }

    public List<String> listAutoSendResourcePacks() {
        return autoSendResourcePacks.stream().map(pack -> pack.getPackFile().getName()).toList();
    }

    public long reloadResourcePacks() {
        final long startTime = System.currentTimeMillis();
        if (resourceDir == null) throw new IllegalStateException("Invalid resource directory.");

        Map<String, String> existingPackHashes = new HashMap<>();
        Map<String, Resourcepack> existingPacks = new HashMap<>(resourcePacks);

        for (Resourcepack pack : resourcePacks.values()) {
            existingPackHashes.put(pack.getPackFile().getName(), pack.getHash());
        }
        resourcePacks.clear();

        File[] files = resourceDir.listFiles();
        if (files != null) {
            Set<String> fileNames = Arrays.stream(files)
                    .filter(File::isFile)
                    .map(File::getName)
                    .filter(name -> name.endsWith(".zip"))
                    .collect(Collectors.toSet());

            Map<String, Resourcepack> newPacks = Arrays.stream(files)
                    .parallel()
                    .filter(File::isFile)
                    .filter(file -> file.getName().endsWith(".zip"))

                    .collect(Collectors.toMap(
                            File::getName,
                            file -> {
                                String fileHash = PackUtils.calcSHA1String(file);
                                String fileName = file.getName();

                                if (!existingPackHashes.containsKey(fileName)) {
                                    LOGGER.info("New file detected: {}", fileName);
                                } else if (!existingPackHashes.get(fileName).equals(fileHash)) {
                                    LOGGER.info("File modified: {}", fileName);
                                    Resourcepack updatedPack = addResourcePack(file);
                                    Bukkit.getOnlinePlayers().forEach(player -> {
                                        if (hasPackEnabled(player, fileName)) {
                                            existingPacks.get(fileName).remove(player);
                                            updatedPack.send(player, true, Optional.of("Pack Update: " + fileName));
                                        }
                                    });
                                    return updatedPack;
                                }
                                return addResourcePack(file);
                            }
                    ));

            resourcePacks.putAll(newPacks);

            Set<String> deletedPackNames = existingPackHashes.keySet().stream()
                    .filter(packName -> !fileNames.contains(packName))
                    .collect(Collectors.toSet());

            deletedPackNames.forEach(s -> LOGGER.info("File deleted: {}", s));

            Bukkit.getOnlinePlayers().forEach(player ->
                    deletedPackNames.forEach(packName -> {
                        if (hasPackEnabled(player, packName)) {
                            existingPacks.get(packName).remove(player);
                        }
            }));
        }
        return (System.currentTimeMillis() - startTime);
    }

    public void clearEnabledPacksCache(Player player) {
        playerPacks.remove(player.getUniqueId());
    }

    public List<String> getEnabledPacks(Player player) {
        return new ArrayList<>(playerPacks.getOrDefault(player.getUniqueId(), Collections.emptyList()));
    }

    public boolean hasPackEnabled(Player player, String fileName) {
        List<String> packs = playerPacks.get(player.getUniqueId());
        return packs != null && packs.contains(fileName);
    }

    public void init() {
        startHttpServer();
        @SuppressWarnings("unchecked")
        List<String> autoSendPacks = CONFIG.get("autosendpacks", List.class);
        if (autoSendPacks != null) {
            autoSendPacks.stream()
                    .map(this::getResourcePack)
                    .filter(Objects::nonNull)
                    .forEach(autoSendResourcePacks::add);
        }
    }


    public void disable() {
        stopHttpServer();
        PackManager.CONFIG.save();
    }

    private void startHttpServer() {
        try {
            httpServer = new HttpServer(PORT, resourceDir.getAbsolutePath());
            httpServer.start();
            LOGGER.info("Http server started on port: {}, listening on directory: {}", PORT, resourceDir.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to start HTTP server", e);
        }
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop();
            LOGGER.info("Http server closed.");
        }
    }
}
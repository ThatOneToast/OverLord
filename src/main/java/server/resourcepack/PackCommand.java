package server.resourcepack;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import server.OverLord;
import server.NMSUtils;
import server.brigadier.CommandWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class PackCommand extends CommandWrapper {
    private static final PackManager packManager = OverLord.getPackManager();
    
    public PackCommand() {
        super("pack");
    }

    @Override
    protected void buildCommand(LiteralArgumentBuilder<CommandSourceStack> builder, CommandBuildContext commandBuildContext) {
        builder.requires(sourceStack -> sourceStack.getBukkitSender().isOp());

        builder.then(Commands.literal("list")
                .executes(this::executeListCommand));

        builder.then(Commands.literal("listautosendpack")
                .executes(this::executeListAutoSendPacksCommand));

        builder.then(Commands.literal("reloadpacks")
                .executes(this::executeReloadCommand));

        builder.then(Commands.literal("send")
                .then(Commands.argument("players", EntityArgument.players())
                        .then(Commands.argument("filename", StringArgumentType.string())
                                .suggests(this::suggestResourcePacks)
                                .executes(this::executeSendPackCommand))));

        builder.then(Commands.literal("remove")
                .then(Commands.argument("players", EntityArgument.players())
                        .then(Commands.argument("filename", StringArgumentType.string())
                                .suggests(this::suggestResourcePacks)
                                .executes(this::executeRemoveSpecificPackCommand))
                        .executes(this::executeRemoveAllPacksCommand)));

        builder.then(Commands.literal("listplayerpacks")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(this::executeListPlayerPacksCommand)));

        builder.then(Commands.literal("isenabled")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("filename", StringArgumentType.string())
                                .suggests(this::suggestResourcePacks)
                                .executes(this::executeIsPackEnabledCommand))));

        builder.then(Commands.literal("addautosendpack")
                .then(Commands.argument("filename", StringArgumentType.string())
                        .suggests(this::suggestResourcePacks)
                        .then(Commands.argument("updateplayers", BoolArgumentType.bool())
                                .executes(this::executesAddAutoSendPack))
                )
        );

        builder.then(Commands.literal("removeautosendpack")
                .then(Commands.argument("filename", StringArgumentType.string())
                        .suggests(this::suggestAutoSendResourcePacks)
                        .then(Commands.argument("updateplayers", BoolArgumentType.bool())
                                .executes(this::executesRemoveAutoSendPack))
                )
        );
    }

    private int executeListAutoSendPacksCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> resourcePacks = new ArrayList<>(packManager.listAutoSendResourcePacks());

        if(resourcePacks.isEmpty()) {
            Component fail = Component.text("No available auto send resource packs.", NamedTextColor.RED);
            source.getBukkitSender().sendMessage(fail);
            return 0;
        }

        Component component = Component.text("Available auto send resource packs:", NamedTextColor.DARK_GRAY);
        for (String resourcePack : resourcePacks) {
            component = component.append(Component.newline())
                    .append(Component.text("- " + resourcePack, NamedTextColor.DARK_AQUA));
        }

        source.getBukkitSender().sendMessage(component);
        return 1;
    }

    private int executesRemoveAutoSendPack(CommandContext<CommandSourceStack> context) {
        String fileName = StringArgumentType.getString(context, "filename");
        boolean updatePlayers = BoolArgumentType.getBool(context, "updateplayers");
        CommandSourceStack source = context.getSource();

        Resourcepack resourcepack = packManager.getResourcePack(fileName);
        if(resourcepack == null) {
            source.sendFailure(NMSUtils.literal("Resource Pack not found"));
            return 0;
        }
        // removed here
        boolean success = packManager.removeAutoSendPack(resourcepack, updatePlayers);
        if(success) source.sendSuccess(NMSUtils.supplierLiteral("Removed resource pack " + fileName + " from auto send list."), true);
        else source.sendFailure(NMSUtils.literal("Resource pack not in auto send list."));
        return 1;
    }

    private int executesAddAutoSendPack(CommandContext<CommandSourceStack> context) {
        String fileName = StringArgumentType.getString(context, "filename");
        boolean updatePlayers = BoolArgumentType.getBool(context, "updateplayers");
        CommandSourceStack source = context.getSource();

        Resourcepack resourcepack = packManager.getResourcePack(fileName);
        if(resourcepack == null) {
            source.sendFailure(NMSUtils.literal("Resource Pack not found"));
            return 0;
        }
        //added here
        boolean success = packManager.addAutoSendPack(resourcepack, updatePlayers);
        if(success) source.sendSuccess(NMSUtils.supplierLiteral("Added resource pack " + fileName + " to auto send list."), true);
        else source.sendFailure(NMSUtils.literal("Resource pack already added to auto send list."));
        return 1;
    }

    private CompletableFuture<Suggestions> suggestResourcePacks(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String input = builder.getRemaining().toLowerCase();

        packManager.listResourcePacks().stream()
                .filter(pack -> pack.toLowerCase().startsWith(input))
                .sorted()
                .forEach(builder::suggest);

        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestAutoSendResourcePacks(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String input = builder.getRemaining().toLowerCase();
        @SuppressWarnings("unchecked")
        List<String> autoSendPacks = PackManager.CONFIG.get("autosendpacks", List.class);
        if(autoSendPacks == null) {
            return builder.buildFuture();
        }

        autoSendPacks.stream()
                .filter(pack -> pack.toLowerCase().startsWith(input))
                .sorted()
                .forEach(builder::suggest);

        return builder.buildFuture();
    }

    private int executeListCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> resourcePacks = new ArrayList<>(packManager.listResourcePacks());

        if(resourcePacks.isEmpty()) {
            Component fail = Component.text("No available resource packs.", NamedTextColor.RED);
            source.getBukkitSender().sendMessage(fail);
            return 0;
        }

        Component component = Component.text("Available resource packs:", NamedTextColor.DARK_GRAY);
        for (String resourcePack : resourcePacks) {
            component = component.append(Component.newline())
                    .append(Component.text("- " + resourcePack, NamedTextColor.DARK_AQUA));
        }

        source.getBukkitSender().sendMessage(component);
        return 1;
    }

    private int executeReloadCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        long time = packManager.reloadResourcePacks();
        source.sendSuccess(NMSUtils.supplierLiteral(String.format("Resource packs reloaded in %dms.", time)), false);
        return 1;
    }

    private int executeSendPackCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "players");
        String fileName = StringArgumentType.getString(context, "filename");
        CommandSourceStack source = context.getSource();

        if (!packManager.packExists(fileName)) {
            source.sendFailure(NMSUtils.literal("Resource pack not found: " + fileName));
            return 0;
        }

        int playerCount = players.size();
        if (playerCount == 0) {
            source.sendFailure(NMSUtils.literal("No players found."));
            return 0;
        }

        int successfulSends = 0;
        for (ServerPlayer player : players) {
            boolean success = packManager.sendPack(player.getBukkitEntity(), fileName, true, Optional.empty());
            if (success) successfulSends++;
        }

        if (successfulSends == 1) source.sendSuccess(NMSUtils.supplierLiteral(String.format("Resource pack %s sent to %s.", fileName, players.iterator().next().getBukkitEntity().getName())), false);
        else if (successfulSends > 1) source.sendSuccess(NMSUtils.supplierLiteral(String.format("Resource pack %s sent to %d players.", fileName, successfulSends)), false);
        else {
            source.sendFailure(NMSUtils.literal(playerCount == 1
                    ? String.format("Resource pack %s could not be sent to %s.", fileName, players.iterator().next().getBukkitEntity().getName())
                    : String.format("Resource pack %s could not be sent to any of the specified players.", fileName)));
        }
        return 1;
    }

    private int executeRemoveSpecificPackCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "players");
        String fileName = StringArgumentType.getString(context, "filename");
        CommandSourceStack source = context.getSource();

        if (!packManager.packExists(fileName)) {
            source.sendFailure(NMSUtils.literal("Resource pack not found: " + fileName));
            return 0;
        }

        int playerCount = players.size();
        if (playerCount == 0) {
            source.sendFailure(NMSUtils.literal("No players found."));
            return 0;
        }

        int successfulRemovals = 0;
        int failedRemovals = 0;

        for (ServerPlayer player : players) {
            boolean success = packManager.removePack(player.getBukkitEntity(), fileName);
            if (success) successfulRemovals++;
            else failedRemovals++;
        }

        if (successfulRemovals > 0) source.sendSuccess(NMSUtils.supplierLiteral(String.format("Resource pack %s removed for %s.", fileName, successfulRemovals == 1 ? players.iterator().next().getBukkitEntity().getName() : successfulRemovals + " players.")), false);
        if (failedRemovals > 0) source.sendFailure(NMSUtils.literal(String.format("Resource pack %s could not be removed from %s.", fileName, failedRemovals == 1 ? players.iterator().next().getBukkitEntity().getName() : failedRemovals + " players.")));
        if (successfulRemovals == 0 && failedRemovals == 0) source.sendFailure(NMSUtils.literal("No players found or failed to remove resource pack from any players."));
        return 1;
    }

    private int executeRemoveAllPacksCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "players");
        CommandSourceStack source = context.getSource();

        int playerCount = players.size();
        if (playerCount == 0) {
            source.sendFailure(NMSUtils.literal("No players found."));
            return 0;
        }

        int successfulRemovals = 0;
        int failedRemovals = 0;

        for (ServerPlayer player : players) {
            boolean success = packManager.removeAllPacks(player.getBukkitEntity());
            if (success) successfulRemovals++;
            else failedRemovals++;
        }

        if (successfulRemovals > 0) source.sendSuccess(NMSUtils.supplierLiteral(String.format("All resource packs removed for %s.", successfulRemovals == 1 ? players.iterator().next().getBukkitEntity().getName() : successfulRemovals + " players.")), false);
        if (failedRemovals > 0) source.sendFailure(NMSUtils.literal(String.format("No resource packs were removed from %s.", failedRemovals == 1 ? "one player." : failedRemovals + " players.")));
        if (successfulRemovals == 0 && failedRemovals == 0) source.sendFailure(NMSUtils.literal("No players found or failed to remove resource packs from any players."));
        return 1;
    }

    private int executeListPlayerPacksCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        CommandSourceStack source = context.getSource();

        List<String> packs = packManager.getEnabledPacks(player.getBukkitEntity());
        if (packs.isEmpty()) {
            source.sendFailure(NMSUtils.literal(String.format("%s has no enabled packs.", player.getBukkitEntity().getName())));
            return 0;
        }

        Component component = Component.text(String.format("Resource packs enabled for %s:", player.getBukkitEntity().getName()), NamedTextColor.DARK_GRAY);

        for (String pack : packs) {
            component = component.append(Component.newline())
                    .append(Component.text("- " + pack, NamedTextColor.DARK_AQUA));
        }

        source.getBukkitSender().sendMessage(component);
        return 1;
    }

    private int executeIsPackEnabledCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        String fileName = StringArgumentType.getString(context, "filename");
        CommandSourceStack source = context.getSource();

        if (!packManager.packExists(fileName)) {
            source.sendFailure(NMSUtils.literal("Resource pack not found: " + fileName));
            return 0;
        }

        boolean isEnabled = packManager.hasPackEnabled(player.getBukkitEntity(), fileName);
        String message = isEnabled
                ? String.format("Resource pack %s is enabled for %s.", fileName, player.getBukkitEntity().getName())
                : String.format("Resource pack %s is not enabled for %s.", fileName, player.getBukkitEntity().getName());
        source.sendSuccess(NMSUtils.supplierLiteral(message), false);
        return 1;
    }
    
}
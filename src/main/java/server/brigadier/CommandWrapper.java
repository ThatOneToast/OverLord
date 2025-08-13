package server.brigadier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;

public abstract class CommandWrapper {
    private final String name;

    public CommandWrapper(String name) {
        this.name = name;
    }

    protected abstract void buildCommand(LiteralArgumentBuilder<CommandSourceStack> builder,  CommandBuildContext commandRegistryAccess);

    public void register(CommandDispatcher<CommandSourceStack> dispatcher,  CommandBuildContext commandRegistryAccess) {
        LiteralArgumentBuilder<CommandSourceStack> commandBuilder = LiteralArgumentBuilder.literal(name);
        buildCommand(commandBuilder, commandRegistryAccess);
        dispatcher.register(commandBuilder);
    }
}

package server;


import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

public class NMSUtils {

    public static Supplier<Component> supplierLiteral(String message) {
        return ()-> Component.literal(message);
    }

    public static Component literal(String message) {
        return Component.literal(message);
    }
}
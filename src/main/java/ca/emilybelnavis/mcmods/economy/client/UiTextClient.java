package ca.emilybelnavis.mcmods.economy.client;

import ca.emilybelnavis.mcmods.economy.banking.UiText;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client-only rendering of presentation values; never used to build a financial request. */
public final class UiTextClient {
    private UiTextClient() {}
    public static String render(String message) { return UiText.render(message, Minecraft.getInstance().options.languageCode); }
    public static Component component(String message) { return Component.literal(render(message)); }
}

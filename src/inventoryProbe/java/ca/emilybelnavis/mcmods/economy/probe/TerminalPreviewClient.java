package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.terminal.TerminalCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Opt-in, release-excluded GPU inventory model review. */
@Mod(value = "economy_inventory_probe", dist = Dist.CLIENT)
public final class TerminalPreviewClient {
    private boolean opened;
    private int frames;
    public TerminalPreviewClient() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        if (Boolean.getBoolean("economy.kioskUpgradeProbe")) new KioskUpgradeClient();
        if (Boolean.getBoolean("economy.villageGenerationProbe")) new VillageGenerationClient();
        if (Boolean.getBoolean("economy.bankLocateProbe")) new BankLocateClient();
        if (Boolean.getBoolean("economy.bankStructureProbe")) new BankStructureClient();
        if (Boolean.getBoolean("economy.bankerProbe")) new BankerClient();
        if (Boolean.getBoolean("economy.traderCustodyProbe")) new TraderCustodyClient();
        if (Boolean.getBoolean("economy.simulationProductionProbe")) new SimulationProductionClient();
        if (Boolean.getBoolean("economy.traderHookProbe")) new TraderHookClient();
        if (Boolean.getBoolean("economy.villageBankAcceptance")) new VillageBankAcceptanceClient();
    }
    private void tick(ClientTickEvent.Post event) {
        if (Boolean.getBoolean("economy.simulationProductionProbe") || Boolean.getBoolean("economy.traderCustodyProbe") || Boolean.getBoolean("economy.traderHookProbe") || Boolean.getBoolean("economy.villageBankAcceptance") || Boolean.getBoolean("economy.bankerProbe") || Boolean.getBoolean("economy.bankingProbe") || Boolean.getBoolean("economy.kioskUpgradeProbe") || Boolean.getBoolean("economy.villageGenerationProbe") || Boolean.getBoolean("economy.bankLocateProbe") || Boolean.getBoolean("economy.bankStructureProbe")) return;
        var minecraft = Minecraft.getInstance();
        if (!opened && minecraft.getOverlay() == null && minecraft.screen != null && minecraft.level == null) {
            opened = true;
            minecraft.setScreen(new Preview());
        }
        if (opened && minecraft.screen instanceof Preview && ++frames == 40) {
            Screenshot.grab(minecraft.gameDirectory, "terminal-inventory.png", minecraft.getMainRenderTarget(),
                    message -> System.out.println("TERMINAL_PREVIEW_SAVED " + message.getString()));
        }
    }
    private static final class Preview extends Screen {
        Preview() { super(Component.literal("Economy terminal inventory model review")); }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xff18232d);
            graphics.drawCenteredString(font, title, width / 2, 8, 0xffd8eee9);
            var entries = TerminalCatalog.all();
            int cellWidth = width / 9, cellHeight = (height - 40) / 4;
            for (int index = 0; index < entries.size(); index++) {
                var entry = entries.get(index);
                int x = (index % 9) * cellWidth + cellWidth / 2;
                int y = 28 + (index / 9) * cellHeight;
                graphics.pose().pushPose();
                graphics.pose().translate(x - 16, y, 0);
                graphics.pose().scale(2, 2, 2);
                graphics.renderItem(new ItemStack(entry.item().get()), 0, 0);
                graphics.pose().popPose();
                String name = entry.id().replace("_atm", "").replace("_register", "").replace('_', ' ');
                graphics.drawCenteredString(font, name, x, y + 36, 0xffe2ebe9);
            }
        }
        @Override public boolean isPauseScreen() { return false; }
    }
}

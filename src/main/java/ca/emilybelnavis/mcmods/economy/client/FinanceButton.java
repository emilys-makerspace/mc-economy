package ca.emilybelnavis.mcmods.economy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Flat terminal control with unshadowed text and optional bank branding. */
public final class FinanceButton extends Button {
    private Integer accent;
    public FinanceButton themed(int color) { accent = color; return this; }

    public FinanceButton(int x, int y, int width, int height, Component label, OnPress press) {
        super(x, y, width, height, label, press, DEFAULT_NARRATION);
    }

    @Override public void renderString(GuiGraphics graphics, net.minecraft.client.gui.Font font, int color) {
        int textWidth = font.width(getMessage());
        int available = width - 4;
        int x = getX() + (width - textWidth) / 2;
        if (textWidth > available) {
            int overflow = textWidth - available;
            double seconds = net.minecraft.Util.getMillis() / 1000.0;
            double duration = Math.max(overflow * .5, 3);
            double progress = Math.sin(Math.PI / 2 * Math.cos(Math.PI * 2 * seconds / duration)) / 2 + .5;
            x = getX() + 2 - (int) (progress * overflow);
        }
        graphics.drawString(font, getMessage(), x, getY() + (height - 9) / 2 + 1, color, false);
    }

    @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int edge = !active ? 0xff344344 : isHoveredOrFocused() ? 0xff79e3d3 : 0xff288f84;
        int fill = !active ? 0xff141d20 : isHoveredOrFocused() ? 0xff173c3c : 0xff0b171b;
        if (accent != null) {
            edge = active ? accent : 0xffaaa69c;
            fill = isHoveredOrFocused() && active ? 0xffe7dfcd : BankingTheme.CREAM;
        }
        graphics.fill(getX(), getY(), getX() + width, getY() + height, edge);
        graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, fill);
        if (isFocused()) graphics.fill(getX() + 2, getY() + height - 2, getX() + width - 2, getY() + height - 1, 0xffb5fff1);
        graphics.enableScissor(getX() + 2, getY() + 1, getX() + width - 2, getY() + height - 1);
        try { renderString(graphics, Minecraft.getInstance().font, accent != null ? (!active ? 0xff969185 : 0xff263238) : !active ? 0xff687f80 : 0xffe4f4ef); }
        finally { graphics.disableScissor(); }
    }
}

package ca.emilybelnavis.mcmods.economy.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Presentation-only geometry and scrolling shared by the small dialogue folios. */
final class FolioLayout {
    private static final int LINE_HEIGHT = 10;
    int width, height, bodyHeight, actionTop, amountY, footerY;
    int scroll, page;
    private List<FormattedCharSequence> rows = List.of();

    void arrange(Font font, int width, int height, List<String> lines, String status,
                 Function<String, Component> component, int choices) {
        this.width = width; this.height = height;
        bodyHeight = height - 144;
        actionTop = height - 90; amountY = height - 44; footerY = height - 24;
        var wrapped = new ArrayList<FormattedCharSequence>();
        for (String line : lines) wrapped.addAll(font.split(component.apply(line), width - 24));
        if (!status.isBlank()) {
            wrapped.add(FormattedCharSequence.EMPTY);
            wrapped.addAll(font.split(component.apply(status), width - 24));
        }
        rows = List.copyOf(wrapped);
        scroll = Math.clamp(scroll, 0, maxScroll());
        page = Math.clamp(page, 0, Math.max(0, (choices - 1) / 4));
    }
    int visibleRows() { return Math.max(1, bodyHeight / LINE_HEIGHT); }
    int maxScroll() { return Math.max(0, rows.size() - visibleRows()); }
    void move(int delta) { scroll = Math.clamp(scroll + delta, 0, maxScroll()); }
    void reset() { scroll = 0; page = 0; }
    int firstChoice() { return page * 4; }
    int choiceWidth() { return (width - 24) / 2; }
    int choiceX(int index) { return 10 + index % 2 * (choiceWidth() + 4); }
    int choiceY(int index) { return actionTop + index / 2 * 22; }
    FinanceButton control(int x, int y, int width, Component label, Runnable action) {
        var button = new FinanceButton(x, y, width, 18, label, ignored -> action.run());
        button.setTooltip(Tooltip.create(label));
        return button;
    }
    void draw(GuiGraphics graphics, Font font, int color) {
        for (int i = 0; i < visibleRows() && scroll + i < rows.size(); i++)
            graphics.drawString(font, rows.get(scroll + i), 12, 30 + i * LINE_HEIGHT, color, false);
        graphics.drawString(font, (rows.isEmpty() ? 0 : scroll + 1) + "–" + Math.min(rows.size(), scroll + visibleRows()) + " / " + rows.size(),
                12, 32 + bodyHeight, color, false);
    }
    boolean overText(double x, double y, int left, int top) {
        return x >= left + 8 && x < left + width - 8 && y >= top + 28 && y < top + actionTop;
    }
    static String focusKey(GuiEventListener listener) {
        return listener instanceof AbstractWidget widget ? widget.getMessage().getString() : null;
    }
    static GuiEventListener findFocus(List<? extends GuiEventListener> widgets, String key) {
        if (key == null) return null;
        return widgets.stream().filter(w -> key.equals(focusKey(w))).findFirst().orElse(null);
    }
    Component scrollLabel(boolean up) {
        return Component.translatable(up ? "screen.economy.scroll_up" : "screen.economy.scroll_down");
    }
}

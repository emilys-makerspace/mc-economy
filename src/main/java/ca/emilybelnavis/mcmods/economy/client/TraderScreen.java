package ca.emilybelnavis.mcmods.economy.client;

import ca.emilybelnavis.mcmods.economy.trade.menu.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Renders complete server descriptions and paged choices; it never calculates a trade. */
public final class TraderScreen extends AbstractContainerScreen<TraderMenu> {
    private final FolioLayout folio = new FolioLayout();
    private String resizeFocus;
    private long rendered = -1;
    public TraderScreen(TraderMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }
    public static void initialize(IEventBus bus) { bus.addListener((RegisterMenuScreensEvent event) -> event.register(TraderMenuCatalog.TRADER.get(), TraderScreen::new)); }
    @Override protected void init() {
        resizeFocus = FolioLayout.focusKey(getFocused());
        imageWidth = Math.min(420, width - 12); imageHeight = Math.min(300, height - 12);
        super.init(); rebuild();
    }
    private void rebuild() {
        String focused = FolioLayout.focusKey(getFocused());
        if (focused == null) focused = resizeFocus;
        resizeFocus = null;
        if (rendered != menu.revision()) focused = null;
        final long offeredRevision = menu.revision();
        if (rendered != menu.revision()) folio.reset();
        clearWidgets(); rendered = menu.revision();
        folio.arrange(font, imageWidth, imageHeight, menu.lines(), menu.status(), Component::literal, menu.choices().size());
        for (int i = folio.firstChoice(); i < Math.min(menu.choices().size(), folio.firstChoice() + 4); i++) {
            var choice = menu.choices().get(i); int slot = i - folio.firstChoice();
            var button = addRenderableWidget(folio.control(leftPos + folio.choiceX(slot), topPos + folio.choiceY(slot), folio.choiceWidth(),
                    Component.literal((choice.selected() ? "✓ " : "") + choice.label()), () -> {
                        if (offeredRevision == menu.revision()) TraderNetwork.action(menu, choice.action(), choice.value()); else rebuild();
                    }));
            button.active = !menu.busy() || choice.action().equals("cancel") || choice.action().equals("exit");
        }
        var up = addRenderableWidget(folio.control(leftPos + imageWidth - 62, topPos + 28 + folio.bodyHeight, 24, Component.literal("↑"), () -> { folio.move(-folio.visibleRows()); rebuild(); }));
        up.setTooltip(net.minecraft.client.gui.components.Tooltip.create(folio.scrollLabel(true))); up.active = folio.scroll > 0;
        var down = addRenderableWidget(folio.control(leftPos + imageWidth - 34, topPos + 28 + folio.bodyHeight, 24, Component.literal("↓"), () -> { folio.move(folio.visibleRows()); rebuild(); }));
        down.setTooltip(net.minecraft.client.gui.components.Tooltip.create(folio.scrollLabel(false))); down.active = folio.scroll < folio.maxScroll();
        var previous = addRenderableWidget(folio.control(leftPos + 10, topPos + folio.footerY, 44, Component.literal("←"), () -> { folio.page--; rebuild(); })); previous.active = folio.page > 0;
        var next = addRenderableWidget(folio.control(leftPos + 58, topPos + folio.footerY, 44, Component.literal("→"), () -> { folio.page++; rebuild(); })); next.active = folio.firstChoice() + 4 < menu.choices().size();
        setFocused(FolioLayout.findFocus(children(), focused));
    }
    @Override protected void containerTick() { super.containerTick(); if (rendered != menu.revision()) rebuild(); }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP || key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN) {
            folio.move(key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP ? -folio.visibleRows() : folio.visibleRows());
            rebuild(); return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (folio.overText(x, y, leftPos, topPos)) { folio.move(vertical > 0 ? -3 : 3); rebuild(); return true; }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override protected void renderBg(GuiGraphics g, float partialTick, int x, int y) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff288f84);
        g.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xff0b171b);
        g.fill(leftPos + 8, topPos + 28, leftPos + imageWidth - 8, topPos + 28 + folio.bodyHeight, 0xff14272c);
    }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {
        g.drawString(font, "TRADER", 10, 12, 0xffe4f4ef, false); folio.draw(g, font, 0xffe4f4ef);
        g.drawString(font, (folio.page + 1) + " / " + Math.max(1, (menu.choices().size() + 3) / 4), 110, folio.footerY + 5, 0xffe4f4ef, false);
    }
    @Override public void render(GuiGraphics g, int x, int y, float partialTick) { super.render(g, x, y, partialTick); renderTooltip(g, x, y); }
}

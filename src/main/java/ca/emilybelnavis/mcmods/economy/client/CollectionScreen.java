package ca.emilybelnavis.mcmods.economy.client;

import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.collection.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Server-offered collection choices, with complete terms and a reserved amount row. */
public final class CollectionScreen extends AbstractContainerScreen<CollectionMenu> {
    private final FolioLayout folio = new FolioLayout();
    private String resizeFocus;
    private long renderedRevision = -1;
    private String draft = "";
    private EditBox amount;
    public CollectionScreen(CollectionMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }
    public static void initialize(IEventBus bus) { bus.addListener((RegisterMenuScreensEvent event) -> event.register(CollectionMenuCatalog.COLLECTION.get(), CollectionScreen::new)); }
    private static Component ui(String key) { return UiTextClient.component(UiText.t(key)); }
    @Override protected void init() {
        resizeFocus = FolioLayout.focusKey(getFocused());
        imageWidth = Math.min(420, width - 12); imageHeight = Math.min(300, height - 12);
        super.init(); rebuild();
    }
    private void rebuild() {
        String focused = FolioLayout.focusKey(getFocused());
        if (focused == null) focused = resizeFocus;
        resizeFocus = null;
        if (renderedRevision != menu.revision()) focused = null;
        final long offeredRevision = menu.revision();
        if (amount != null) draft = amount.getValue();
        if (renderedRevision != menu.revision()) folio.reset();
        clearWidgets(); amount = null; renderedRevision = menu.revision();
        var choices = menu.choices().stream().filter(c -> !c.action().equals("exit")).toList();
        folio.arrange(font, imageWidth, imageHeight, menu.lines(), menu.status(), UiTextClient::component, choices.size());
        for (int i = folio.firstChoice(); i < Math.min(choices.size(), folio.firstChoice() + 4); i++) {
            var choice = choices.get(i); int slot = i - folio.firstChoice();
            var button = addRenderableWidget(folio.control(leftPos + folio.choiceX(slot), topPos + folio.choiceY(slot), folio.choiceWidth(),
                    UiTextClient.component((choice.selected() ? "✓ " : "") + choice.label()), () -> {
                        if (offeredRevision != menu.revision()) { rebuild(); return; }
                        String value = choice.action().equals("preview_partial") ? (amount == null ? draft : amount.getValue()) : choice.value();
                        CollectionNetwork.action(menu, choice.action(), value);
                    }));
            button.active = !menu.busy() || choice.action().equals("retry");
        }
        if (choices.stream().anyMatch(c -> c.action().equals("preview_partial"))) {
            amount = addRenderableWidget(new EditBox(font, leftPos + 10, topPos + folio.amountY, imageWidth - 20, 18, ui("ui.client.contract_payment")));
            amount.setHint(ui("ui.client.contract_amount"));
            amount.setTooltip(net.minecraft.client.gui.components.Tooltip.create(ui("ui.client.numeric_convention")));
            amount.setMaxLength(22); amount.setValue(draft); amount.setEditable(!menu.busy());
        }
        var up = addRenderableWidget(folio.control(leftPos + imageWidth - 62, topPos + 28 + folio.bodyHeight, 24, Component.literal("↑"), () -> { folio.move(-folio.visibleRows()); rebuild(); }));
        up.setTooltip(net.minecraft.client.gui.components.Tooltip.create(folio.scrollLabel(true))); up.active = folio.scroll > 0;
        var down = addRenderableWidget(folio.control(leftPos + imageWidth - 34, topPos + 28 + folio.bodyHeight, 24, Component.literal("↓"), () -> { folio.move(folio.visibleRows()); rebuild(); }));
        down.setTooltip(net.minecraft.client.gui.components.Tooltip.create(folio.scrollLabel(false))); down.active = folio.scroll < folio.maxScroll();
        var previous = addRenderableWidget(folio.control(leftPos + 10, topPos + folio.footerY, 36, Component.literal("←"), () -> { folio.page--; rebuild(); })); previous.active = folio.page > 0;
        var next = addRenderableWidget(folio.control(leftPos + 50, topPos + folio.footerY, 36, Component.literal("→"), () -> { folio.page++; rebuild(); })); next.active = folio.firstChoice() + 4 < choices.size();
        addRenderableWidget(folio.control(leftPos + imageWidth - 104, topPos + folio.footerY, 94, ui("ui.client.exit"), () -> CollectionNetwork.action(menu, "exit", "")));
        setFocused(FolioLayout.findFocus(children(), focused));
    }
    @Override protected void containerTick() { super.containerTick(); if (renderedRevision != menu.revision()) rebuild(); }
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
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xffb79d70);
        g.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xff17252c);
        g.fill(leftPos + 8, topPos + 28, leftPos + imageWidth - 8, topPos + 28 + folio.bodyHeight, 0xff0e1b22);
    }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {
        g.drawString(font, ui("ui.client.collection_title"), 10, 11, 0xffdfc795, false);
        folio.draw(g, font, 0xffd9e4e6);
        int count = (int) menu.choices().stream().filter(c -> !c.action().equals("exit")).count();
        g.drawString(font, (folio.page + 1) + " / " + Math.max(1, (count + 3) / 4), 94, folio.footerY + 5, 0xffd9e4e6, false);
    }
    @Override public void render(GuiGraphics g, int x, int y, float partialTick) { super.render(g, x, y, partialTick); renderTooltip(g, x, y); }
}

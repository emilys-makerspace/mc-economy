package ca.emilybelnavis.mcmods.economy.client;

import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.banker.dialogue.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
/** Explicit questions at left; answers appear only after selection. */
public final class BankerScreen extends AbstractContainerScreen<BankerMenu> {
    private static String ui(String key, Object... args) {
        return UiTextClient.render(UiText.t(key, args));
    }

    private long rendered=-1;
    public BankerScreen(BankerMenu menu,Inventory inventory,Component title){super(menu,inventory,title);imageWidth=376;imageHeight=224;}
    public static void initialize(IEventBus bus){bus.addListener((RegisterMenuScreensEvent e)->e.register(BankerMenuCatalog.BANKER.get(),BankerScreen::new));}
    @Override protected void init(){super.init();rebuild();}
    private void rebuild(){clearWidgets();rendered=menu.revision();int row=0;for(var choice:menu.choices()){addRenderableWidget(new FinanceButton(leftPos+10,topPos+34+row*25,166,21,UiTextClient.component(choice.label()),b->BankerNetwork.action(menu,choice.action(),choice.value())));row++;}}
    @Override protected void containerTick(){super.containerTick();if(rendered!=menu.revision())rebuild();}
    @Override protected void renderBg(GuiGraphics g,float partialTick,int x,int y){g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xff5c6953);g.fill(leftPos+2,topPos+2,leftPos+imageWidth-2,topPos+imageHeight-2,0xffe9e4d3);g.fill(leftPos+183,topPos+32,leftPos+365,topPos+211,0xfff7f3e8);}
    @Override protected void renderLabels(GuiGraphics g,int x,int y){g.drawString(font,ui("ui.client.banker_title"),10,12,0xff304333,false);int lineY=40;for(var line:menu.lines())for(var wrapped:font.split(UiTextClient.component(line),166)){if(lineY>196)break;g.drawString(font,wrapped,191,lineY,0xff293b31,false);lineY+=11;}}
    @Override public void render(GuiGraphics g,int x,int y,float partialTick){super.render(g,x,y,partialTick);renderTooltip(g,x,y);}
}

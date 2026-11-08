package ca.emilybelnavis.mcmods.economy.client;

import ca.emilybelnavis.mcmods.economy.banking.UiText;

import ca.emilybelnavis.mcmods.economy.collection.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Two-panel collection folio with server-offered choices and explicit payment review. */
public final class CollectionScreen extends AbstractContainerScreen<CollectionMenu> {
    private static String ui(String key, Object... args) {
        return UiTextClient.render(UiText.t(key, args));
    }

    private long renderedRevision=-1;
    private String draft="";
    private EditBox amount;
    public CollectionScreen(CollectionMenu menu,Inventory inventory,Component title){super(menu,inventory,title);imageWidth=340;imageHeight=230;}
    public static void initialize(IEventBus bus){bus.addListener(CollectionScreen::register);}
    private static void register(RegisterMenuScreensEvent event){event.register(CollectionMenuCatalog.COLLECTION.get(),CollectionScreen::new);}
    protected void init(){super.init();rebuild();}
    private void rebuild(){
        if(amount!=null)draft=amount.getValue();clearWidgets();amount=null;renderedRevision=menu.revision();
        int row=0;
        for(var choice:menu.choices()){
            if(choice.action().equals("exit"))continue;
            var button=addRenderableWidget(new FinanceButton(leftPos+10,topPos+40+row*22,122,19,UiTextClient.component((choice.selected()?"✓ ":"")+choice.label()),b->{
                String value=choice.action().equals("preview_partial")?(amount==null?draft:amount.getValue()):choice.value();
                CollectionNetwork.action(menu,choice.action(),value);
            }));
            button.active=!menu.busy()||choice.action().equals("retry");row++;
        }
        if(menu.choices().stream().anyMatch(c->c.action().equals("preview_partial"))){
            amount=new EditBox(font,leftPos+145,topPos+139,181,18,UiTextClient.component(ui("ui.client.contract_payment")));
            amount.setTooltip(net.minecraft.client.gui.components.Tooltip.create(UiTextClient.component(UiText.t("ui.client.numeric_convention"))));
            amount.setMaxLength(22);amount.setValue(draft);amount.setEditable(!menu.busy());addRenderableWidget(amount);
        }
        addRenderableWidget(new FinanceButton(leftPos+10,topPos+204,122,18,UiTextClient.component(ui("ui.client.exit")),b->CollectionNetwork.action(menu,"exit","")));
    }
    protected void containerTick(){super.containerTick();if(renderedRevision!=menu.revision())rebuild();}
    protected void renderBg(GuiGraphics g,float partialTick,int mouseX,int mouseY){
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xffb79d70);
        g.fill(leftPos+1,topPos+1,leftPos+imageWidth-1,topPos+imageHeight-1,0xff17252c);
        g.fill(leftPos+7,topPos+33,leftPos+136,topPos+228,0xff233239);
        g.fill(leftPos+140,topPos+33,leftPos+334,topPos+184,0xff0e1b22);
        g.fill(leftPos+140,topPos+188,leftPos+334,topPos+224,0xff24373d);
    }
    protected void renderLabels(GuiGraphics g,int mouseX,int mouseY){
        g.drawString(font,ui("ui.client.collection_title"),10,11,0xffdfc795,false);
        g.drawString(font,ui("ui.client.private_dialogue"),10,23,0xff9fb9be,false);
        int y=40,rows=0;
        for(String line:menu.lines())for(var wrapped:font.split(UiTextClient.component(line),182)){
            if(rows++>=15)break;g.drawString(font,wrapped,146,y,0xffd9e4e6,false);y+=9;
        }
        if(amount!=null)g.drawString(font,ui("ui.client.contract_amount"),145,127,0xffdfc795,false);
        int statusY=193,count=0;
        for(var line:font.split(UiTextClient.component(menu.status()),182)){
            if(count++>=3)break;g.drawString(font,line,145,statusY,0xffc5d8d9,false);statusY+=9;
        }
    }
    public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick){super.render(g,mouseX,mouseY,partialTick);renderTooltip(g,mouseX,mouseY);}
}

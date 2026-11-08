package ca.emilybelnavis.mcmods.economy.client;

import ca.emilybelnavis.mcmods.economy.trade.menu.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Renders only server-issued descriptions and choices; it never calculates a trade. */
public final class TraderScreen extends AbstractContainerScreen<TraderMenu> {
    private long rendered=-1;
    public TraderScreen(TraderMenu menu,Inventory inventory,Component title){super(menu,inventory,title);imageWidth=420;imageHeight=264;}
    public static void initialize(IEventBus bus){bus.addListener((RegisterMenuScreensEvent event)->event.register(TraderMenuCatalog.TRADER.get(),TraderScreen::new));}
    @Override protected void init(){imageWidth=Math.min(420,width-12);imageHeight=Math.min(264,height-12);super.init();rebuild();}
    private void rebuild(){
        clearWidgets();rendered=menu.revision();int half=imageWidth/2,buttonWidth=(half-18)/2;
        int rowHeight=Math.min(23,(imageHeight-76)/8),index=0;
        for(var choice:menu.choices()){
            String label=(choice.selected()?"✓ ":"")+choice.label();if(font.width(label)>buttonWidth-8)label=font.plainSubstrByWidth(label,Math.max(8,buttonWidth-16))+"…";
            var button=new FinanceButton(leftPos+half+6+(index%2)*(buttonWidth+4),topPos+32+(index/2)*rowHeight,
                    buttonWidth,rowHeight-2,Component.literal(label),ignored->TraderNetwork.action(menu,choice.action(),choice.value()));
            button.active=!menu.busy()||choice.action().equals("cancel")||choice.action().equals("exit");
            button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(choice.label())));
            addRenderableWidget(button);index++;
        }
    }
    @Override protected void containerTick(){super.containerTick();if(rendered!=menu.revision())rebuild();}
    @Override protected void renderBg(GuiGraphics graphics,float partialTick,int x,int y){
        graphics.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xff288f84);
        graphics.fill(leftPos+1,topPos+1,leftPos+imageWidth-1,topPos+imageHeight-1,0xff0b171b);
        graphics.fill(leftPos+7,topPos+30,leftPos+imageWidth/2-3,topPos+imageHeight-43,0xff14272c);
    }
    @Override protected void renderLabels(GuiGraphics graphics,int x,int y){
        graphics.drawString(font,"TRADER",10,12,0xffe4f4ef,false);
        graphics.enableScissor(leftPos+9,topPos+32,leftPos+imageWidth/2-5,topPos+imageHeight-45);
        try{
            int lineY=34;
            for(var line:menu.lines())for(var wrapped:font.split(Component.literal(line),imageWidth/2-24)){
                graphics.drawString(font,wrapped,11,lineY,0xffe4f4ef,false);lineY+=10;
            }
        }finally{graphics.disableScissor();}
        graphics.enableScissor(leftPos+9,topPos+imageHeight-38,leftPos+imageWidth-9,topPos+imageHeight-5);
        try{
            int lineY=imageHeight-36;
            for(var wrapped:font.split(Component.literal(menu.status()),imageWidth-22)){
                graphics.drawString(font,wrapped,11,lineY,0xffb5fff1,false);lineY+=10;
            }
        }finally{graphics.disableScissor();}
    }
    @Override public void render(GuiGraphics graphics,int x,int y,float partialTick){super.render(graphics,x,y,partialTick);renderTooltip(graphics,x,y);}
}

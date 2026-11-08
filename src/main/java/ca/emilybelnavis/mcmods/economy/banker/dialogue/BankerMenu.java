package ca.emilybelnavis.mcmods.economy.banker.dialogue;
import ca.emilybelnavis.mcmods.economy.banker.StaffingRuntime;
import ca.emilybelnavis.mcmods.economy.banker.entity.BankerEntity;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.banking.UiPublication;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
/** No inventory or financial state; every action revalidates the server-owned assignment. */
public final class BankerMenu extends AbstractContainerMenu {
    private final Inventory inventory;
    private final UUID entity,assignment,session;
    private final long assignmentRevision;
    private long revision,lastActionTick=-10;
    private List<String> lines=List.of();
    private List<BankerNetwork.Choice> choices=List.of();
    private String page="questions",status=UiText.t("ui.common.choose_question");
    public BankerMenu(int id,Inventory inventory,UUID entity,UUID assignment,long assignmentRevision,UUID session){super(BankerMenuCatalog.BANKER.get(),id);this.inventory=inventory;this.entity=entity;this.assignment=assignment;this.assignmentRevision=assignmentRevision;this.session=session;}
    static BankerMenu fromNetwork(int id,Inventory inventory,RegistryFriendlyByteBuf b){return new BankerMenu(id,inventory,b.readUUID(),b.readUUID(),b.readVarLong(),b.readUUID());}
    public void writeOpening(RegistryFriendlyByteBuf b){b.writeUUID(entity);b.writeUUID(assignment);b.writeVarLong(assignmentRevision);b.writeUUID(session);}
    public UUID session(){return session;}public long revision(){return revision;}public List<String> lines(){return lines;}public List<BankerNetwork.Choice> choices(){return choices;}public String status(){return status;}
    public BankerEntity entity(ServerPlayer player){return player.serverLevel().getEntity(entity) instanceof BankerEntity banker?banker:null;}
    public boolean valid(ServerPlayer player){
        if(player!=inventory.player||player.containerMenu!=this||!player.isAlive()||player.server.getPlayerList().getPlayer(player.getUUID())!=player)return false;
        var banker=entity(player);if(banker==null||!banker.isAlive()||banker.isRemoved()||banker.distanceToSqr(player)>36||!assignment.equals(banker.assignmentId())||assignmentRevision!=banker.assignmentRevision())return false;
        return StaffingRuntime.context(player,banker).filter(c->assignment.equals(c.assignment())&&assignmentRevision==c.assignmentRevision()).isPresent();
    }
    public boolean accept(ServerPlayer player,BankerNetwork.Action action){
        if(action.menuId()!=containerId||!session.equals(action.session())||revision!=action.revision()||!valid(player))return false;
        if(!choices.stream().anyMatch(c->c.action().equals(action.action())&&c.value().equals(action.value())))return false;
        long tick=player.server.getTickCount();if(tick-lastActionTick<2)return false;lastActionTick=tick;return true;
    }
    public void publish(String page,List<String> lines,List<BankerNetwork.Choice> choices) {
        if (!(inventory.player instanceof ServerPlayer player)) return;
        String language=UiText.language(player);
        BankerNetwork.Snapshot snapshot;
        try {
            var text=UiPublication.render(lines,UiText.t("ui.common.choose_question_or_bank"),choices.stream().map(BankerNetwork.Choice::label).toList(),language,16);
            var controls=new ArrayList<BankerNetwork.Choice>();
            for(int i=0;i<choices.size();i++){var c=choices.get(i);controls.add(new BankerNetwork.Choice(c.action(),c.value(),text.labels().get(i),c.selected()));}
            snapshot=new BankerNetwork.Snapshot(containerId,session,Math.addExact(revision,1),page,text.lines(),text.status(),false,controls);
        } catch(IllegalArgumentException unavailable) {
            snapshot=new BankerNetwork.Snapshot(containerId,session,Math.addExact(revision,1),page,
                    List.of(UiText.render(UiText.t("ui.common.view_unavailable"),language)),
                    UiText.render(UiText.t("ui.common.choose_question"),language),false,List.of());
        }
        apply(snapshot);BankerNetwork.send(player,snapshot);
    }
    public void apply(BankerNetwork.Snapshot snapshot){if(snapshot.menuId()!=containerId||!session.equals(snapshot.session())||snapshot.revision()<=revision)return;revision=snapshot.revision();page=snapshot.page();lines=snapshot.lines();status=snapshot.status();choices=snapshot.choices();}
    @Override public boolean stillValid(Player player){return player instanceof ServerPlayer p?valid(p):player==inventory.player;}
    @Override public ItemStack quickMoveStack(Player player,int index){return ItemStack.EMPTY;}
    @Override public void clicked(int slot,int button,ClickType type,Player player){}
    @Override public boolean canDragTo(Slot slot){return false;}
}

package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.banking.BankingMenu;
import ca.emilybelnavis.mcmods.economy.card.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Live physical-card admission. The worker reads detached revocation flags, never Minecraft objects. */
public final class CreditDrawAdmission {
    private record Lease(ServerPlayer player,BankingMenu menu,CreditFacilities.DrawRequest request,TerminalPolicy.Snapshot terminal,Proof proof) {}
    private static final Map<MinecraftServer,List<Lease>> LEASES=new IdentityHashMap<>();
    private CreditDrawAdmission() {}
    public static final class Proof implements CreditFacilities.LiveDraw {
        private final AtomicBoolean live=new AtomicBoolean(true);
        private final CreditFacilities.DrawRequest request;
        private final TerminalPolicy.Snapshot terminal;
        private String design;
        private Proof(CreditFacilities.DrawRequest request,TerminalPolicy.Snapshot terminal){this.request=request;this.terminal=terminal;}
        public boolean valid(Journal.Intent intent,JournalEngine.ReadView reads,CreditFacilities.Token token,CreditFacilities.DrawKind kind,ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId destination){
            if(!CreditCardDesigns.id(token.issuer(),token.network().key(),token.tier()).equals(design) || !live.get() || System.currentTimeMillis()>=request.expiresAt() || !intent.request().equals(request.request()) || !intent.actor().equals(request.owner())
                    || !token.id().equals(request.token()) || kind!=request.kind() || !destination.equals(request.destination())
                    || !TerminalPolicy.matches(reads,request.world(),terminal.anchor(),terminal.generation(),terminal.revision()))return false;
            if(kind==CreditFacilities.DrawKind.PURCHASE){
                var price=RegisterSales.price(reads,request.world(),terminal.anchor()).orElse(null);
                var facility=CreditFacilities.find(reads,token.facility()).orElse(null);
                return facility!=null && price!=null && price.currency()==facility.currency() && price.generation().equals(terminal.generation()) && price.terminalRevision()==terminal.revision()
                    && price.sellerAccount().equals(destination.value()) && price.sellerAccountRevision()==request.destinationRevision()
                    && price.amount().minorUnits()==request.amountMinor();
            }
            var facility=CreditFacilities.find(reads,token.facility()).orElse(null);
            return facility!=null && request.fees()!=null && CreditFacilities.AdvanceFees.capture(reads,facility,terminal).equals(request.fees());
        }
    }
    /** Capture after displaying the server-generated draw quote and receiving confirmation. */
    public static Proof capture(ServerPlayer player,BankingMenu menu,CreditFacilities.DrawRequest request,TerminalPolicy.Snapshot terminal){
        if(!player.server.isSameThread())throw new IllegalStateException("Server thread required");
        if(terminal==null || !terminal.active() || !terminal.world().equals(request.world()) || !terminal.anchor().equals(menu.anchor()) || !terminal.generation().equals(menu.generation())
                || !request.owner().value().equals(player.getUUID()) || request.expiresAt()>System.currentTimeMillis()+30_000L
                || (request.kind()==CreditFacilities.DrawKind.ADVANCE?(menu.access()!=BankingMenu.Access.ATM && menu.access()!=BankingMenu.Access.TELLER):menu.access()!=BankingMenu.Access.REGISTER))throw new IllegalArgumentException("CREDIT_PHYSICAL_ADMISSION");
        var proof=new Proof(request,terminal);
        proof.design=java.util.stream.Stream.of(player.getMainHandItem(),player.getOffhandItem()).filter(stack->CardStackIdentity.mark(stack,request.world()).filter(m->m.token().equals(request.token())).isPresent()).map(stack->net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()).findFirst().orElse("");
        var lease=new Lease(player,menu,request,terminal,proof);
        if(!current(lease))throw new IllegalArgumentException("CREDIT_PHYSICAL_ADMISSION");
        var list=LEASES.computeIfAbsent(player.server,k->new ArrayList<>());if(list.size()>=256)throw new IllegalStateException("Credit admission busy");list.add(lease);return proof;
    }
    private static boolean current(Lease lease){
        var p=lease.player();var r=lease.request();
        if(p.server.getPlayerList().getPlayer(p.getUUID())!=p || !p.isAlive() || p.containerMenu!=lease.menu() || !lease.menu().valid(p) || System.currentTimeMillis()>=r.expiresAt())return false;
        return java.util.stream.Stream.of(p.getMainHandItem(),p.getOffhandItem()).anyMatch(stack->
                CardStackIdentity.mark(stack,r.world()).filter(m->m.token().equals(r.token())).isPresent()
                && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(lease.proof().design)
                && CreditCardDesigns.byId(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()).filter(d->d.network().equals(r.network().key())).isPresent());
    }
    public static void tick(MinecraftServer server){var list=LEASES.get(server);if(list!=null)list.removeIf(l->{if(current(l))return false;l.proof().live.set(false);return true;});}
    public static void stop(MinecraftServer server){var list=LEASES.remove(server);if(list!=null)list.forEach(l->l.proof().live.set(false));}
}

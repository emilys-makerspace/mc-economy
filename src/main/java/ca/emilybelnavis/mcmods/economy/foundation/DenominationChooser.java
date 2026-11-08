package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.banking.UiText;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalService;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** One chooser per running world. All lease and admission state is confined to the server thread. */
public final class DenominationChooser {
    private final ServerServices services;
    private final FoundationStore store;
    private ServerPlayer holder;
    private UUID lease;
    private Journal.Intent accepted;
    private boolean known;
    private boolean reading;
    private boolean open;
    private boolean submitting;
    private boolean uncertain;
    private long nextProbe;
    private final JournalService journal;

    public DenominationChooser(ServerServices services, FoundationStore store, Clock wall) {
        this.services = services;
        this.store = store;
        journal = services.journal((actor, kind) -> actor instanceof PlayerId && kind.equals(FxService.OPEN),
                Map.of(FxService.OPEN, FxService.opening(wall)), new JournalService.Publisher() {
                    public void invalidate(Journal.Outcome committed) {}
                    public void publish(Journal.Outcome terminal) {}
                });
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Function<MinecraftServer, DenominationChooser> sessions) {
        dispatcher.register(Commands.literal("economy").then(Commands.literal("choose")
                                .then(Commands.argument("currency", StringArgumentType.word()).suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(EconomyCurrency.all().stream().map(EconomyCurrency::code), builder)).executes(context -> {
                    var source = context.getSource();
                    var chooser = sessions.apply(source.getServer());
                    var player = source.getPlayer();
                    if (chooser == null || player == null || source.source != player
                            || !chooser.choose(player, chooser.lease,
                            StringArgumentType.getString(context, "currency").toUpperCase(java.util.Locale.ROOT))) {
                        source.sendFailure(UiText.component(source, UiText.t("ui.dialogue.currency_choice_unavailable_use_the_current_.870ee0")));
                        return 0;
                    }
                    return 1;
                }))));
    }

    public void tick(MinecraftServer server) {
        if (services.state() != ServerServices.State.READY || open) return;
        if (!known) {
            if (!reading && System.nanoTime() >= nextProbe) {
                reading = true;
                nextProbe = System.nanoTime() + 1_000_000_000L;
                services.storage(() -> store.transaction(tx -> FxService.latest(tx::query) != null))
                        .whenComplete((exists, error) -> {
                            reading = false;
                            if (error == null) {
                                known = true;
                                open = exists;
                            }
                        });
            }
            return;
        }
        if (accepted != null) {
            if (!submitting && System.nanoTime() >= nextProbe) submit();
            return;
        }
        var players = server.getPlayerList().getPlayers();
        if (holder != null && players.contains(holder)) return;
        holder = players.isEmpty() ? null : players.getFirst();
        lease = holder == null ? null : UUID.randomUUID();
        if (holder != null) ca.emilybelnavis.mcmods.economy.banking.BankingController.openWorldSetup(holder, lease);
        if (holder != null) holder.sendSystemMessage(UiText.component(holder, UiText.t("ui.dialogue.world_currency_prompt", EconomyCurrency.all().toString())));
    }

    public boolean canChoose(ServerPlayer player, UUID suppliedLease) {
        return known && !open && accepted == null && holder == player && lease != null && lease.equals(suppliedLease);
    }
    public boolean choose(ServerPlayer player, UUID suppliedLease, String code) {
        if (!known || open || accepted != null || holder != player || (suppliedLease == null || !suppliedLease.equals(lease))) return false;
        try {
            EconomyCurrency.fromCode(code);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        accepted = new Journal.Intent(new OperationId(UUID.randomUUID()), new RequestId(UUID.randomUUID()),
                new PlayerId(player.getUUID()), FxService.OPEN, new Journal.CanonicalRequest(1, Map.of("currency", code)),
                null, null);
        submit();
        return true;
    }

    private void submit() {
        submitting = true;
        nextProbe = System.nanoTime() + 1_000_000_000L;
        journal.submit(accepted).completion().thenAccept(delivery -> {
            submitting = false;
            var outcome = delivery.outcome();
            if (outcome.status() == Journal.Status.UNKNOWN) {
                uncertain = true;
                return;
            }
            if (uncertain && outcome.status() == Journal.Status.NOT_ACCEPTED
                    && !outcome.result().code().equals("ABSENT_RETRY_SAME_ID")) return;
            uncertain = false;
            open = outcome.status() == Journal.Status.COMMITTED || outcome.result().code().equals("FX_ALREADY_OPEN");
            if (open && holder != null && holder.server.getPlayerList().getPlayer(holder.getUUID()) == holder) {
                holder.sendSystemMessage(UiText.component(holder, UiText.t("ui.dialogue.world_currency_selected.cba0c2")));
            }
            accepted = null;
            holder = null;
            lease = null;
        });
    }
}

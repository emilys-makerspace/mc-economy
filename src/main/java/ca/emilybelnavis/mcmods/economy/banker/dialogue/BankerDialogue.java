package ca.emilybelnavis.mcmods.economy.banker.dialogue;

import ca.emilybelnavis.mcmods.economy.banker.StaffingRuntime;
import ca.emilybelnavis.mcmods.economy.banker.entity.BankerEntity;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

/** Question answers are disclosed only after an explicit offered selection. */
public final class BankerDialogue {
  private static final Map<String, String> QUESTIONS;

  static {
    var q = new LinkedHashMap<String, String>();
    q.put("opening", UiText.t("ui.dialogue.how_do_i_open_an_account.e2bdfe"));
    q.put("eligibility", UiText.t("ui.dialogue.am_i_eligible.a455e6"));
    q.put("institution", UiText.t("ui.dialogue.which_bank_is_this.c66a92"));
    q.put("currency", UiText.t("ui.dialogue.what_is_the_native_currency.1cf20c"));
    q.put("basics", UiText.t("ui.dialogue.how_does_banking_work.620458"));
    QUESTIONS = Collections.unmodifiableMap(q);
  }

  private BankerDialogue() {}

  public static void open(ServerPlayer player, BankerEntity banker) {
    if (StaffingRuntime.context(player, banker).isEmpty()) return;
    UUID session = UUID.randomUUID();
    player.openMenu(
        new SimpleMenuProvider(
            (id, inventory, p) ->
                new BankerMenu(
                    id,
                    inventory,
                    banker.getUUID(),
                    banker.assignmentId(),
                    banker.assignmentRevision(),
                    session),
            UiText.component(player, UiText.t("ui.dialogue.banker.b23d73"))),
        b -> {
          b.writeUUID(banker.getUUID());
          b.writeUUID(banker.assignmentId());
          b.writeVarLong(banker.assignmentRevision());
          b.writeUUID(session);
        });
    if (player.containerMenu instanceof BankerMenu menu)
      menu.publish("questions", List.of(), choices());
  }

  private static List<BankerNetwork.Choice> choices() {
    var choices = new ArrayList<BankerNetwork.Choice>();
    QUESTIONS.forEach(
        (id, label) -> choices.add(new BankerNetwork.Choice("ask", id, label, false)));
    choices.add(
        new BankerNetwork.Choice(
            "banking", "", UiText.t("ui.dialogue.open_banking_services.f360ba"), false));
    choices.add(
        new BankerNetwork.Choice("exit", "", UiText.t("ui.dialogue.goodbye.b5b29c"), false));
    return List.copyOf(choices);
  }

  public static void action(ServerPlayer player, BankerMenu menu, String action, String value) {
    if (!menu.valid(player)) {
      player.closeContainer();
      return;
    }
    if (action.equals("exit")) {
      player.closeContainer();
      return;
    }
    var banker = menu.entity(player);
    var context = StaffingRuntime.context(player, banker);
    if (context.isEmpty()) {
      player.closeContainer();
      return;
    }
    if (action.equals("banking")) {
      StaffingRuntime.openBanking(player, banker);
      return;
    }
    if (!action.equals("ask") || !QUESTIONS.containsKey(value)) return;
    var c = context.get();
    String answer =
        switch (value) {
          case "opening" ->
              UiText.t("ui.dialogue.choose_banking_services_then_review_the_acco.3368c9");
          case "eligibility" ->
              UiText.t("ui.dialogue.eligibility_depends_on_this_bank_s_current_r.aa286a");
          case "institution" ->
              UiText.t(
                  "ui.dialogue.institution_answer",
                  ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.require(
                          new ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog
                              .InstitutionId(c.institution()))
                      .name());
          case "currency" -> UiText.t("ui.dialogue.currency_answer", c.currency());
          case "basics" ->
              UiText.t("ui.dialogue.banking_services_let_you_review_eligible_acc.a324b6");
          default -> throw new IllegalArgumentException("Question");
        };
    menu.publish("answer", List.of(QUESTIONS.get(value), answer), choices());
  }
}

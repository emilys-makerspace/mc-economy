package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.time.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/**
 * One bounded frozen owner query per live menu; cursor handles never encode financial authority.
 */
public final class BankingHistory {
  private BankingHistory() {}

  private static final Map<BankingMenu, State> STATES = new WeakHashMap<>();

  private static final class State {
    UUID generation = UUID.randomUUID();
    OwnerHistory.Query query;
    Long highWater;
    final List<OwnerHistory.Key> boundaries = new ArrayList<>();
    final Map<String, Link> links = new HashMap<>();
    int index;
    long request;
    List<OwnerHistory.Movement> rows = List.of();
    RateQuery rates =
        new RateQuery(
            BankingHistoryView.Family.EFFECTIVE,
            EconomyCurrency.CAD,
            EconomyCurrency.USD,
            null,
            null,
            null);
    RatePage ratePage;

    State(UUID selected) {
      query = new OwnerHistory.Query(selected, null, null, null, OwnerHistory.Type.ALL, 8);
      boundaries.add(null);
    }

    void reset(OwnerHistory.Query next) {
      query = next;
      generation = UUID.randomUUID();
      highWater = null;
      boundaries.clear();
      boundaries.add(null);
      links.clear();
      index = 0;
      rows = List.of();
    }
  }

  private record Link(UUID generation, Integer page, UUID transaction) {}

  public record RateQuery(
      BankingHistoryView.Family family,
      EconomyCurrency source,
      EconomyCurrency target,
      Long selectedAt,
      FxPairHistory.Cursor daily,
      FxPairHistory.ExactCursor exact) {
    public RateQuery {
      Objects.requireNonNull(family);
      Objects.requireNonNull(source);
      Objects.requireNonNull(target);
      if (source == target || selectedAt != null && selectedAt < 0)
        throw new IllegalArgumentException("FX history query");
    }
  }

  public record RatePage(
      BankingHistoryView.Rates view, FxPairHistory.Cursor daily, FxPairHistory.ExactCursor exact) {}

  public static boolean handles(String action) {
    return BankingHistoryView.ACTIONS.contains(action);
  }

  public static void closed(BankingMenu menu) {
    STATES.remove(menu);
  }

  public static void action(ServerPlayer player, BankingMenu menu, String action, String value) {
    var audience = FinanceMenuSupport.audience(player, menu);
    if (!audience.live() || menu.busy() || !handles(action)) return;
    var services = BankingController.services(player);
    var grant = FinanceMenuSupport.grant(player, services);
    if (grant == null) return;
    var selected = BankingAccounts.selected(menu);
    var state =
        STATES.computeIfAbsent(
            menu, ignored -> new State(selected == null ? null : selected.value()));
    long request = ++state.request;
    try {
      boolean dateInput =
          !value.isEmpty() && (action.equals("history_dates") || action.equals("rate_at"));
      if (!action.equals("history")
          && !dateInput
          && menu.choices().stream()
              .noneMatch(c -> c.action().equals(action) && c.value().equals(value)))
        throw new IllegalArgumentException("History control is stale");
      switch (action) {
        case "history" -> {
          state.reset(
              new OwnerHistory.Query(
                  selected == null ? null : selected.value(),
                  null,
                  null,
                  null,
                  OwnerHistory.Type.ALL,
                  8));
          page(audience, services, grant, state, request, 0);
        }
        case "history_refresh" -> {
          state.reset(state.query);
          page(audience, services, grant, state, request, 0);
        }
        case "history_next", "history_prev" -> {
          var link = state.links.get(value);
          if (link == null || !state.generation.equals(link.generation()) || link.page() == null)
            throw new IllegalArgumentException("History cursor audience changed");
          page(audience, services, grant, state, request, link.page());
        }
        case "history_detail" -> {
          var link = state.links.get(value);
          if (link == null
              || !state.generation.equals(link.generation())
              || link.transaction() == null
              || state.highWater == null)
            throw new IllegalArgumentException("History detail was not offered");
          menu.setBusy(true);
          services
              .ledger()
              .historyDetail(grant, link.transaction(), state.highWater)
              .whenComplete(
                  (found, error) -> {
                    if (!live(audience, state, request)) return;
                    if (error != null || found.isEmpty()) {
                      unavailable(
                          audience, state, UiText.t("ui.bank.captured_detail_unavailable.f76ae7"));
                      return;
                    }
                    var d = found.orElseThrow();
                    publish(
                        audience,
                        state,
                        "history_detail",
                        detailLines(d),
                        List.of(
                            choice(
                                "history_back",
                                state.generation.toString(),
                                UiText.t("ui.bank.back_to_frozen_history.b9bc7e"))),
                        new BankingHistoryView(
                            state.generation,
                            state.highWater,
                            state.query,
                            List.of(),
                            d,
                            null,
                            "AVAILABLE"));
                  });
        }
        case "history_back" -> {
          if (!state.generation.toString().equals(value))
            throw new IllegalArgumentException("History query changed");
          page(audience, services, grant, state, request, state.index);
        }
        case "history_filters" -> filters(audience, state);
        case "history_account", "history_acc_next" -> {
          if (action.equals("history_account") && !value.isEmpty()) {
            setQuery(
                state,
                value.equals("all") ? null : UUID.fromString(value),
                state.query.currency(),
                state.query.fromInclusive(),
                state.query.toExclusive(),
                state.query.type(),
                state.query.limit());
            page(audience, services, grant, state, request, 0);
          } else
            accounts(
                audience,
                services,
                grant,
                state,
                request,
                value.isEmpty() ? null : new AccountId(UUID.fromString(value)));
        }
        case "history_currency" -> {
          if (value.isEmpty()) {
            var choices = new ArrayList<BankingNetwork.Choice>();
            choices.add(choice(action, "all", UiText.t("ui.bank.all_currencies.36d9e2")));
            for (var c : EconomyCurrency.all()) choices.add(choice(action, c.code(), c.code()));
            selector(audience, state, UiText.t("ui.bank.currency_filter.f1edbf"), choices);
          } else {
            setQuery(
                state,
                state.query.account(),
                value.equals("all") ? null : EconomyCurrency.fromCode(value),
                state.query.fromInclusive(),
                state.query.toExclusive(),
                state.query.type(),
                state.query.limit());
            page(audience, services, grant, state, request, 0);
          }
        }
        case "history_type" -> {
          if (value.isEmpty()) {
            var choices = new ArrayList<BankingNetwork.Choice>();
            for (var t : OwnerHistory.Type.values())
              choices.add(choice(action, t.name(), display(t)));
            selector(audience, state, UiText.t("ui.bank.transaction_type_filter.21eda9"), choices);
          } else {
            setQuery(
                state,
                state.query.account(),
                state.query.currency(),
                state.query.fromInclusive(),
                state.query.toExclusive(),
                OwnerHistory.Type.valueOf(value),
                state.query.limit());
            page(audience, services, grant, state, request, 0);
          }
        }
        case "history_size" -> {
          setQuery(
              state,
              state.query.account(),
              state.query.currency(),
              state.query.fromInclusive(),
              state.query.toExclusive(),
              state.query.type(),
              Integer.parseInt(value));
          page(audience, services, grant, state, request, 0);
        }
        case "history_dates" -> {
          if (value.isEmpty()) dateEntry(audience, state, false);
          else {
            requireInput(menu, "history_dates");
            var fields = value.strip().split("\\s+", -1);
            if (fields.length != 2) throw new IllegalArgumentException("Enter two UTC dates");
            Long from = date(fields[0]), to = date(fields[1]);
            setQuery(
                state,
                state.query.account(),
                state.query.currency(),
                from,
                to,
                state.query.type(),
                state.query.limit());
            page(audience, services, grant, state, request, 0);
          }
        }
        case "history_clear_dates" -> {
          setQuery(
              state,
              state.query.account(),
              state.query.currency(),
              null,
              null,
              state.query.type(),
              state.query.limit());
          page(audience, services, grant, state, request, 0);
        }
        case "history_rates" -> {
          state.rates =
              new RateQuery(
                  BankingHistoryView.Family.EFFECTIVE,
                  EconomyCurrency.CAD,
                  EconomyCurrency.USD,
                  null,
                  null,
                  null);
          rates(audience, services, grant, state, request);
        }
        case "rate_source", "rate_target" -> {
          var q = state.rates;
          if (value.isEmpty()) {
            var choices = new ArrayList<BankingNetwork.Choice>();
            for (var c : EconomyCurrency.all())
              if (c != (action.equals("rate_source") ? q.target() : q.source()))
                choices.add(choice(action, c.code(), c.code()));
            selector(audience, state, UiText.t("ui.bank.directed_fx_pair.70c013"), choices);
          } else {
            state.rates =
                new RateQuery(
                    q.family(),
                    action.equals("rate_source") ? EconomyCurrency.fromCode(value) : q.source(),
                    action.equals("rate_target") ? EconomyCurrency.fromCode(value) : q.target(),
                    q.selectedAt(),
                    null,
                    null);
            rates(audience, services, grant, state, request);
          }
        }
        case "rate_family" -> {
          var q = state.rates;
          state.rates =
              new RateQuery(
                  BankingHistoryView.Family.valueOf(value),
                  q.source(),
                  q.target(),
                  null,
                  null,
                  null);
          rates(audience, services, grant, state, request);
        }
        case "rate_next" -> {
          if (!state.generation.toString().equals(value) || state.ratePage == null)
            throw new IllegalArgumentException("FX cursor changed");
          var q = state.rates;
          state.rates =
              new RateQuery(
                  q.family(),
                  q.source(),
                  q.target(),
                  q.selectedAt(),
                  state.ratePage.daily(),
                  state.ratePage.exact());
          rates(audience, services, grant, state, request);
        }
        case "rate_at" -> {
          if (value.isEmpty()) dateEntry(audience, state, true);
          else {
            requireInput(menu, "rate_at");
            var q = state.rates;
            Long at =
                value.equals("-")
                    ? null
                    : q.family() == BankingHistoryView.Family.DAILY
                        ? date(value)
                        : Instant.parse(value.strip()).toEpochMilli();
            state.rates = new RateQuery(q.family(), q.source(), q.target(), at, null, null);
            rates(audience, services, grant, state, request);
          }
        }
        default -> throw new IllegalArgumentException("Unsupported history control");
      }
    } catch (IllegalArgumentException | DateTimeException | ArithmeticException invalid) {
      audience.decline(
          menu.page(),
          List.of(UiText.t("ui.bank.use_the_offered_history_controls_and_valid_utc.c78884")),
          UiText.t("ui.bank.history_query_invalid.702d19"),
          List.of(choice("history_refresh", "", UiText.t("ui.bank.reset_query.c63886"))));
    }
  }

  private static void page(
      FinanceMenuSupport.Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      long request,
      int index) {
    if (index < 0 || index >= state.boundaries.size())
      throw new IllegalArgumentException("History boundary");
    audience.menu().setBusy(true);
    services
        .ledger()
        .ownerHistory(grant, state.query, state.highWater, state.boundaries.get(index))
        .whenComplete(
            (found, error) -> {
              if (!live(audience, state, request)) return;
              if (error != null || found.isEmpty()) {
                unavailable(audience, state, UiText.t("ui.bank.owner_history_unavailable.6f8cc4"));
                return;
              }
              var result = found.orElseThrow();
              state.highWater = result.highWater();
              state.links.clear();
              var choices = new ArrayList<BankingNetwork.Choice>();
              var lines = new ArrayList<String>();
              lines.add(UiText.t("ui.bank.recorded_utc_newest_first_page.8dd358") + (index + 1));
              if (result.availability() == OwnerHistory.Availability.AVAILABLE) {
                state.index = index;
                state.rows = result.rows();
                for (var row : result.rows()) {
                  String token = UUID.randomUUID().toString();
                  state.links.put(token, new Link(state.generation, null, row.transaction()));
                  choices.add(choice("history_detail", token, movementLabel(row)));
                }
                if (index > 0)
                  choices.add(
                      pageChoice(
                          state, "history_prev", index - 1, UiText.t("ui.bank.previous.50f942")));
                if (result.next() != null) {
                  if (index + 1 < 16) {
                    if (state.boundaries.size() == index + 1) state.boundaries.add(result.next());
                    choices.add(
                        pageChoice(
                            state, "history_next", index + 1, UiText.t("ui.bank.next.bc9819")));
                  } else
                    lines.add(
                        UiText.t("ui.bank.visited_page_limit_reached_refresh_or_refine_f.863c9e"));
                }
                if (result.rows().isEmpty())
                  lines.add(UiText.t("ui.bank.no_posted_movements_match_this_query.99e4c0"));
              } else
                lines.add(
                    switch (result.availability()) {
                      case WORK_LIMIT ->
                          UiText.t("ui.bank.sparse_filter_reached_its_work_limit_refine_th.1ced49");
                      case DENIED ->
                          UiText.t("ui.bank.owner_history_is_unavailable_for_that_account.b04fec");
                      default ->
                          UiText.t("ui.bank.history_index_is_catching_up_refresh_when_read.1f9085");
                    });
              choices.add(choice("history_filters", "", UiText.t("ui.bank.filters.96e578")));
              choices.add(choice("history_refresh", "", UiText.t("ui.bank.refresh.56e3ba")));
              choices.add(
                  choice("history_rates", "", UiText.t("ui.bank.historical_fx_rates.f1da40")));
              publish(
                  audience,
                  state,
                  "history",
                  lines,
                  choices,
                  new BankingHistoryView(
                      state.generation,
                      state.highWater,
                      state.query,
                      result.rows(),
                      null,
                      null,
                      result.availability().name()));
            });
  }

  private static BankingNetwork.Choice pageChoice(
      State state, String action, int index, String label) {
    String token = UUID.randomUUID().toString();
    state.links.put(token, new Link(state.generation, index, null));
    return choice(action, token, label);
  }

  private static void filters(FinanceMenuSupport.Audience a, State s) {
    selector(
        a,
        s,
        UiText.t("ui.bank.filters_use_recorded_utc_not_financial_calenda.dc1b5c"),
        List.of(
            choice("history_account", "", UiText.t("ui.bank.account.85dfa3")),
            choice("history_currency", "", UiText.t("ui.bank.currency.e070de")),
            choice("history_type", "", UiText.t("ui.bank.transaction_type.575da7")),
            choice("history_dates", "", UiText.t("ui.bank.utc_date_range.8d1187")),
            choice("history_clear_dates", "", UiText.t("ui.bank.clear_dates.d3c69a")),
            choice("history_size", "8", UiText.t("ui.bank.8_movements_page.816671")),
            choice("history_size", "16", UiText.t("ui.bank.16_movements_page.ecbeb0"))));
  }

  private static void accounts(
      FinanceMenuSupport.Audience a,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State s,
      long request,
      AccountId after) {
    a.menu().setBusy(true);
    services
        .ledger()
        .historyAccounts(grant, after)
        .whenComplete(
            (found, error) -> {
              if (!live(a, s, request)) return;
              if (error != null || found.isEmpty()) {
                unavailable(a, s, UiText.t("ui.bank.account_filter_unavailable.c70992"));
                return;
              }
              var rows = found.orElseThrow();
              var choices = new ArrayList<BankingNetwork.Choice>();
              choices.add(
                  choice("history_account", "all", UiText.t("ui.bank.all_owned_accounts.21bdf0")));
              for (var r : rows.stream().limit(16).toList())
                choices.add(
                    choice(
                        "history_account",
                        r.id().value().toString(),
                        r.currency().code()
                            + " "
                            + shortId(r.id().value())
                            + " "
                            + display(r.status())));
              if (rows.size() > 16)
                choices.add(
                    choice(
                        "history_acc_next",
                        rows.get(15).id().value().toString(),
                        UiText.t("ui.bank.more_accounts.a2f344")));
              selector(
                  a,
                  s,
                  UiText.t("ui.bank.owned_accounts_including_closed_history.ea397f"),
                  choices);
            });
  }

  private static void selector(
      FinanceMenuSupport.Audience a,
      State s,
      String heading,
      List<BankingNetwork.Choice> controls) {
    var choices = new ArrayList<>(controls);
    choices.add(
        choice(
            "history_back",
            s.generation.toString(),
            UiText.t("ui.bank.back_to_frozen_history.b9bc7e")));
    publish(a, s, "history_filters", List.of(heading, queryLabel(s.query)), choices, null);
  }

  private static void dateEntry(FinanceMenuSupport.Audience a, State s, boolean rates) {
    publish(
        a,
        s,
        rates ? "history_rate_date" : "history_dates",
        List.of(
            rates
                ? (s.rates.family() == BankingHistoryView.Family.DAILY
                    ? UiText.t("ui.bank.utc_day_help")
                    : UiText.t("ui.bank.utc_instant_help"))
                : UiText.t("ui.bank.utc_from_inclusive_to_exclusive_yyyy_mm_dd_yyy.d88cc1"),
            UiText.t("ui.bank.use_for_an_unset_boundary_recorded_utc_is_sepa.d48f8c")),
        List.of(
            choice(
                rates ? "rate_at" : "history_dates",
                "",
                UiText.t("ui.bank.apply_utc_filter.bf607f")),
            choice(
                "history_back",
                s.generation.toString(),
                UiText.t("ui.bank.back_to_frozen_history.b9bc7e"))),
        null);
  }

  private static Long date(String value) {
    return value.equals("-")
        ? null
        : LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
  }

  private static void requireInput(BankingMenu m, String action) {
    if (!m.page().equals(action.equals("rate_at") ? "history_rate_date" : "history_dates")
        || m.choices().stream().noneMatch(c -> c.action().equals(action)))
      throw new IllegalArgumentException("Date input was not offered");
  }

  private static void setQuery(
      State s,
      UUID account,
      EconomyCurrency currency,
      Long from,
      Long to,
      OwnerHistory.Type type,
      int size) {
    s.reset(new OwnerHistory.Query(account, currency, from, to, type, size));
  }

  private static String queryLabel(OwnerHistory.Query q) {
    String range =
        q.fromInclusive() == null && q.toExclusive() == null
            ? ""
            : UiText.t("ui.bank.utc.9f24a3")
                + (q.fromInclusive() == null
                    ? "-"
                    : Instant.ofEpochMilli(q.fromInclusive()).atZone(ZoneOffset.UTC).toLocalDate())
                + " → "
                + (q.toExclusive() == null
                    ? "-"
                    : Instant.ofEpochMilli(q.toExclusive()).atZone(ZoneOffset.UTC).toLocalDate())
                + UiText.t("ui.bank.exclusive");
    return (q.account() == null ? UiText.t("ui.bank.all_accounts.6a19f2") : shortId(q.account()))
        + " · "
        + (q.currency() == null ? UiText.t("ui.bank.all_currencies.7d2a5d") : q.currency().code())
        + " · "
        + display(q.type())
        + " · "
        + q.limit()
        + UiText.t("ui.bank.per_page")
        + range;
  }

  private static String movementLabel(OwnerHistory.Movement r) {
    return (r.internalMovement() ? UiText.t("ui.bank.internal.ab7882") : "")
        + money(r.amount())
        + " · "
        + shortId(r.account())
        + " · "
        + Instant.ofEpochMilli(r.recordedAt()).toString();
  }

  private static List<String> detailLines(HistoryDetails.Detail d) {
    var lines = new ArrayList<String>();
    lines.add(UiText.t("ui.bank.committed_transaction.edbeed") + d.transaction());
    lines.add(UiText.t("ui.bank.original_operation.1268ee") + d.operation());
    lines.add(UiText.t("ui.bank.recorded_utc.caf495") + Instant.ofEpochMilli(d.recordedAt()));
    lines.add(d.service() + " / " + d.operationType());
    for (var m : d.movements()) lines.add(shortId(m.account()) + ": " + money(m.amount()));
    if (d.movementsMore())
      lines.add(UiText.t("ui.bank.additional_owned_movements_are_retained_in_ori.260452"));
    lines.add(
        UiText.t("ui.bank.fee.1069ca")
            + (d.fee().availability() == HistoryDetails.Availability.KNOWN
                ? money(d.fee().amount())
                : display(d.fee().availability())));
    lines.add(
        UiText.t("ui.bank.counterparty.e9a8e2")
            + (d.counterparty().visibility() == HistoryDetails.Visibility.CAPTURED
                ? d.counterparty().label()
                : display(d.counterparty().visibility())));
    for (var f : d.fx()) {
      lines.add(
          UiText.t("ui.bank.captured_fx.09a723")
              + f.source()
              + " → "
              + f.target()
              + " · "
              + display(f.family())
              + UiText.t("ui.bank.utc.9f24a3")
              + Instant.ofEpochMilli(f.effectiveAt()));
      lines.add(
          f.availability() == HistoryDetails.Availability.KNOWN
              ? fxRate(f.numerator(), f.denominator()) + UiText.t("ui.bank.v.0286fb") + f.version()
              : UiText.t("ui.bank.captured_publication_unavailable.6cff1e"));
    }
    if (!d.provenance().isEmpty())
      lines.add(UiText.t("ui.bank.original_sources.3b79fb") + d.provenance().size());
    if (d.additionalDetail())
      lines.add(UiText.t("ui.bank.some_original_detail_is_unavailable_or_exceeds.3542e2"));
    return lines;
  }

  private static String fxRate(String numerator, String denominator) {
    return new java.math.BigDecimal(numerator)
        .divide(new java.math.BigDecimal(denominator), 8, java.math.RoundingMode.DOWN)
        .toPlainString();
  }

  private static void rates(
      FinanceMenuSupport.Audience a,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State s,
      long request) {
    a.menu().setBusy(true);
    services
        .ledger()
        .historyRates(grant, s.rates)
        .whenComplete(
            (found, error) -> {
              if (!live(a, s, request)) return;
              if (error != null || found.isEmpty()) {
                unavailable(
                    a, s, UiText.t("ui.bank.historical_fx_unavailable_for_that_selection.b2cb7d"));
                return;
              }
              s.ratePage = found.orElseThrow();
              var v = s.ratePage.view();
              var lines = new ArrayList<String>();
              lines.add(
                  display(v.family())
                      + " · "
                      + v.source()
                      + " → "
                      + v.target()
                      + UiText.t("ui.bank.informational_only.1eb5b2"));
              for (var r : v.rows()) {
                lines.add("UTC " + Instant.ofEpochMilli(r.effectiveAt()));
                lines.add(
                    r.status().equals("AVAILABLE")
                        ? fxRate(r.numerator(), r.denominator())
                            + UiText.t("ui.bank.v.0286fb")
                            + r.version()
                        : display(r.status()));
                if (r.publication() != null)
                  lines.add(UiText.t("ui.bank.publication.ab3fec") + r.publication());
              }
              if (v.rows().isEmpty())
                lines.add(UiText.t("ui.bank.no_retained_publication_for_this_selection.20ebd4"));
              var choices = new ArrayList<BankingNetwork.Choice>();
              choices.add(choice("rate_source", "", UiText.t("ui.bank.base_currency.b95241")));
              choices.add(choice("rate_target", "", UiText.t("ui.bank.quote_currency.e60880")));
              choices.add(
                  choice(
                      "rate_family",
                      v.family() == BankingHistoryView.Family.EFFECTIVE ? "DAILY" : "EFFECTIVE",
                      UiText.t("ui.bank.switch_publication_family.406ee4")));
              choices.add(choice("rate_at", "", UiText.t("ui.bank.select_utc_time_day.417b55")));
              if (s.ratePage.daily() != null || s.ratePage.exact() != null)
                choices.add(
                    choice(
                        "rate_next",
                        s.generation.toString(),
                        UiText.t("ui.bank.next_publications.533281")));
              choices.add(
                  choice(
                      "history_back",
                      s.generation.toString(),
                      UiText.t("ui.bank.back_to_frozen_history.b9bc7e")));
              publish(
                  a,
                  s,
                  "history_rates",
                  lines,
                  choices,
                  new BankingHistoryView(
                      s.generation,
                      s.highWater == null ? 0 : s.highWater,
                      s.query,
                      List.of(),
                      null,
                      v,
                      "AVAILABLE"));
            });
  }

  public static RatePage readRates(JournalEngine.ReadView reads, WorldId world, RateQuery q) {
    var pair = new FxQuotes.Pair(q.source(), q.target());
    var rows = new ArrayList<BankingHistoryView.Rate>();
    FxPairHistory.Cursor daily = null;
    FxPairHistory.ExactCursor exact = null;
    if (q.family() == BankingHistoryView.Family.EFFECTIVE) {
      var page = FxPairHistory.intradayPageAt(reads, world, pair, q.exact(), 8, q.selectedAt());
      exact = page.next();
      for (var r : page.entries()) {
        var source =
            reads.query(
                "SELECT r.record_version FROM effective_fx_snapshots s JOIN journal_records r ON r.sequence=s.source_sequence AND r.record_type=? WHERE s.id=?",
                List.of(EffectiveFxFacts.FACT, r.id().toString()),
                1);
        rows.add(
            new BankingHistoryView.Rate(
                r.id(),
                r.effectiveMillis(),
                r.numerator().toPlainString(),
                r.denominator().toPlainString(),
                source.isEmpty() ? 0 : Integer.parseInt(source.getFirst().get("record_version")),
                source.isEmpty() ? "PROVENANCE_UNAVAILABLE" : "AVAILABLE"));
      }
    } else {
      if (FxService.latestDaily(reads) == null)
        return new RatePage(
            new BankingHistoryView.Rates(q.family(), q.source(), q.target(), List.of()),
            null,
            null);
      var page =
          FxPairHistory.pageAt(
              reads,
              world,
              pair,
              q.daily(),
              8,
              q.selectedAt() == null
                  ? null
                  : Instant.ofEpochMilli(q.selectedAt()).atZone(ZoneOffset.UTC).toLocalDate());
      daily = page.next();
      for (var r : page.days()) {
        var p = FxService.publication(reads, r.date());
        var version =
            reads.query(
                "SELECT record_version FROM journal_records WHERE record_type=? AND substr(payload,1,10)=? LIMIT 1",
                List.of(FxFacts.PUBLICATION, r.date().toString()),
                1);
        rows.add(
            new BankingHistoryView.Rate(
                p == null ? null : p.updateId(),
                r.date().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                r.numerator() == null ? "" : r.numerator().toPlainString(),
                r.denominator() == null ? "" : r.denominator().toPlainString(),
                version.isEmpty() ? 0 : Integer.parseInt(version.getFirst().get("record_version")),
                r.currencyUnavailable()
                    ? "CURRENCY_UNAVAILABLE"
                    : r.missing() ? "MISSING_PUBLICATION" : "AVAILABLE"));
      }
    }
    return new RatePage(
        new BankingHistoryView.Rates(q.family(), q.source(), q.target(), rows), daily, exact);
  }

  private static boolean live(FinanceMenuSupport.Audience a, State s, long request) {
    return a.live() && STATES.get(a.menu()) == s && s.request == request;
  }

  private static void publish(
      FinanceMenuSupport.Audience a,
      State s,
      String page,
      List<String> lines,
      List<BankingNetwork.Choice> choices,
      BankingHistoryView view) {
    if (!a.live()) return;
    a.menu().setBusy(false);
    a.menu()
        .publish(
            page,
            lines,
            UiText.t("ui.bank.history.767282") + queryLabel(s.query),
            choices,
            BankingOperation.View.ready(),
            null,
            view);
  }

  private static void unavailable(FinanceMenuSupport.Audience a, State s, String message) {
    publish(
        a,
        s,
        "history",
        List.of(message, UiText.t("ui.bank.refresh_or_refine_the_query_no_financial_actio.fe1f7d")),
        List.of(
            choice("history_refresh", "", UiText.t("ui.bank.refresh.56e3ba")),
            choice("history_filters", "", UiText.t("ui.bank.filters.96e578"))),
        null);
  }

  private static BankingNetwork.Choice choice(String action, String value, String label) {
    return new BankingNetwork.Choice(action, value, label, false);
  }

  private static String shortId(UUID id) {
    return id.toString().substring(0, 8);
  }

  private static String money(Money money) {
    return UiText.money(money);
  }

  private static String display(Object value) {
    return switch (String.valueOf(value)) {
      case "ALL" -> UiText.t("ui.bank.state.all");
      case "TRANSFER" -> UiText.t("ui.bank.state.transfer");
      case "FX" -> UiText.t("ui.bank.state.fx");
      case "CASH" -> UiText.t("ui.bank.state.cash");
      case "PAYMENT" -> UiText.t("ui.bank.state.payment");
      case "CREDIT" -> UiText.t("ui.bank.state.credit");
      case "LOAN" -> UiText.t("ui.bank.state.loan");
      case "COLLECTION" -> UiText.t("ui.bank.state.collection");
      case "OTHER" -> UiText.t("ui.bank.state.other");
      case "KNOWN" -> UiText.t("ui.bank.state.known");
      case "NOT_RECORDED" -> UiText.t("ui.bank.state.not_recorded");
      case "UNAVAILABLE" -> UiText.t("ui.bank.state.unavailable");
      case "PRIVATE" -> UiText.t("ui.bank.state.private");
      case "CAPTURED" -> UiText.t("ui.bank.state.captured");
      case "AVAILABLE" -> UiText.t("ui.bank.state.available");
      case "PROVENANCE_UNAVAILABLE" -> UiText.t("ui.bank.state.provenance_unavailable");
      case "CURRENCY_UNAVAILABLE" -> UiText.t("ui.bank.state.currency_unavailable");
      case "MISSING_PUBLICATION" -> UiText.t("ui.bank.state.missing_publication");
      case "EFFECTIVE" -> UiText.t("ui.bank.state.effective");
      case "DAILY" -> UiText.t("ui.bank.state.daily");
      case "OPEN" -> UiText.t("ui.bank.state.open");
      case "CLOSED" -> UiText.t("ui.bank.state.closed");
      case "EXPIRED" -> UiText.t("ui.bank.state.expired");
      case "ACTIVE" -> UiText.t("ui.bank.state.active");
      case "REVOKED" -> UiText.t("ui.bank.state.revoked");
      default -> String.valueOf(value);
    };
  }
}

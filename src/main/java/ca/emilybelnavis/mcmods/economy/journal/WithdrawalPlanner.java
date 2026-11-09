package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import java.util.*;

/** Bounded exact change over immutable server-selected denominations. */
public final class WithdrawalPlanner {
  public static final int MAX_NORMALIZED_AMOUNT = 100_000;
  public static final long MAX_WORK_CELLS = 2_000_000;
  public static final int MAX_ITEMS = 2_304;
  public static final int MAX_DENOMINATIONS = 131;
  private static final int UNREACHABLE = MAX_ITEMS + 1;
  private static final Comparator<Denomination> ORDER =
      Comparator.comparingLong(Denomination::minorUnits).reversed().thenComparing(Denomination::id);

  public record Denomination(String id, EconomyCurrency currency, long minorUnits, boolean bill) {
    public Denomination {
      Objects.requireNonNull(currency);
      if (id == null
          || id.length() > 96
          || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
          || minorUnits < 1) throw failure("WITHDRAW_DENOMINATION");
    }
  }

  public record Line(Denomination denomination, int count) {
    public Line {
      Objects.requireNonNull(denomination);
      if (count < 1 || count > MAX_ITEMS) throw failure("WITHDRAW_QUANTITY");
    }
  }

  public record Plan(
      EconomyCurrency currency,
      long minorUnits,
      Map<String, Integer> bills,
      Map<String, Integer> coins,
      List<Line> lines,
      int itemCount) {
    public Plan {
      Objects.requireNonNull(currency);
      bills = Collections.unmodifiableMap(new TreeMap<>(bills));
      coins = Collections.unmodifiableMap(new TreeMap<>(coins));
      var ordered = new ArrayList<>(lines);
      ordered.sort(Comparator.comparing(Line::denomination, ORDER));
      lines = List.copyOf(ordered);
      if (minorUnits < 1
          || itemCount < 1
          || itemCount > MAX_ITEMS
          || lines.size() > MAX_DENOMINATIONS) throw failure("WITHDRAW_QUANTITY");
      var expectedBills = new TreeMap<String, Integer>();
      var expectedCoins = new TreeMap<String, Integer>();
      var ids = new HashSet<String>();
      long sum = 0;
      int count = 0;
      try {
        for (var line : lines) {
          var denomination = line.denomination();
          if (denomination.currency() != currency || !ids.add(denomination.id()))
            throw failure("WITHDRAW_PLAN");
          (denomination.bill() ? expectedBills : expectedCoins)
              .put(denomination.id(), line.count());
          sum = Math.addExact(sum, Math.multiplyExact(denomination.minorUnits(), line.count()));
          count = Math.addExact(count, line.count());
        }
      } catch (ArithmeticException overflow) {
        throw failure("WITHDRAW_PLAN");
      }
      if (sum != minorUnits
          || count != itemCount
          || !expectedBills.equals(bills)
          || !expectedCoins.equals(coins)) throw failure("WITHDRAW_PLAN");
    }
  }

  private WithdrawalPlanner() {}

  public static Plan plan(
      EconomyCurrency currency,
      long amount,
      List<Denomination> denominations,
      boolean atm,
      Map<String, Integer> billEdits) {
    return billEdits == null
        ? plan(currency, amount, atm, denominations)
        : edited(currency, amount, atm, denominations, billEdits);
  }

  public static Plan plan(
      EconomyCurrency currency, long amount, boolean atm, List<Denomination> denominations) {
    var input = input(currency, amount, atm, denominations);
    var bills = input.denominations().stream().filter(Denomination::bill).toList();
    var coins = input.denominations().stream().filter(value -> !value.bill()).toList();
    var billTable = table(bills, input.units(), input.quantum());
    if (atm) {
      if (billTable.count()[input.units()] == UNREACHABLE) throw failure("WITHDRAW_IMPOSSIBLE");
      return result(
          currency, amount, input.denominations(), reconstruct(billTable, input.units()), Map.of());
    }
    var coinTable = table(coins, input.units(), input.quantum());
    // Descending exact bill value gives the required objective before minimizing bill count.
    for (int billValue = input.units(); billValue >= 0; billValue--) {
      int billCount = billTable.count()[billValue];
      int coinCount = coinTable.count()[input.units() - billValue];
      if (billCount != UNREACHABLE
          && coinCount != UNREACHABLE
          && billCount + coinCount <= MAX_ITEMS)
        return result(
            currency,
            amount,
            input.denominations(),
            reconstruct(billTable, billValue),
            reconstruct(coinTable, input.units() - billValue));
    }
    throw failure("WITHDRAW_IMPOSSIBLE");
  }

  /** Only bill counts are accepted from an editor. Coin quantities are always derived here. */
  public static Plan edited(
      EconomyCurrency currency,
      long amount,
      boolean atm,
      List<Denomination> denominations,
      Map<String, Integer> billEdits) {
    Objects.requireNonNull(billEdits);
    var input = input(currency, amount, atm, denominations);
    if (billEdits.size() > MAX_DENOMINATIONS) throw failure("WITHDRAW_EDIT");
    var allowed = new HashMap<String, Denomination>();
    for (var denomination : denominations) allowed.put(denomination.id(), denomination);
    var bills = new TreeMap<String, Integer>();
    long value = 0;
    int count = 0;
    try {
      for (var edit : billEdits.entrySet()) {
        var denomination = allowed.get(edit.getKey());
        Integer quantity = edit.getValue();
        if (denomination == null
            || !denomination.bill()
            || denomination.currency() != currency
            || quantity == null
            || quantity < 0
            || quantity > MAX_ITEMS) throw failure("WITHDRAW_EDIT");
        value = Math.addExact(value, Math.multiplyExact(denomination.minorUnits(), quantity));
        count = Math.addExact(count, quantity);
        if (quantity > 0) bills.put(denomination.id(), quantity);
      }
    } catch (ArithmeticException overflow) {
      throw failure("WITHDRAW_EDIT");
    }
    if (value > amount || count > MAX_ITEMS) throw failure("WITHDRAW_EDIT");
    long remainder = amount - value;
    if (atm) {
      if (remainder != 0) throw failure("WITHDRAW_IMPOSSIBLE");
      return result(currency, amount, input.denominations(), bills, Map.of());
    }
    if (remainder % input.quantum() != 0) throw failure("WITHDRAW_IMPOSSIBLE");
    var coins =
        input.denominations().stream().filter(denomination -> !denomination.bill()).toList();
    var coinTable = table(coins, input.units(), input.quantum());
    int units = (int) (remainder / input.quantum());
    if (coinTable.count()[units] == UNREACHABLE || count + coinTable.count()[units] > MAX_ITEMS)
      throw failure("WITHDRAW_IMPOSSIBLE");
    return result(currency, amount, input.denominations(), bills, reconstruct(coinTable, units));
  }

  private record Input(List<Denomination> denominations, long quantum, int units) {}

  private static Input input(
      EconomyCurrency currency, long amount, boolean atm, List<Denomination> denominations) {
    Objects.requireNonNull(currency);
    Objects.requireNonNull(denominations);
    if (amount < 1 || denominations.isEmpty() || denominations.size() > MAX_DENOMINATIONS)
      throw failure("WITHDRAW_REQUEST");
    var ids = new HashSet<String>();
    var usable = new ArrayList<Denomination>();
    long quantum = 0;
    for (var denomination : denominations) {
      Objects.requireNonNull(denomination);
      if (!ids.add(denomination.id()) || denomination.currency() != currency)
        throw failure("WITHDRAW_DENOMINATION");
      if ((!atm || denomination.bill()) && denomination.minorUnits() <= amount) {
        usable.add(denomination);
        quantum = gcd(quantum, denomination.minorUnits());
      }
    }
    if (usable.isEmpty() || amount % quantum != 0) throw failure("WITHDRAW_IMPOSSIBLE");
    long units = amount / quantum;
    if (units > MAX_NORMALIZED_AMOUNT || (units + 1) * usable.size() > MAX_WORK_CELLS)
      throw failure("WITHDRAW_SOLVER_LIMIT");
    usable.sort(ORDER);
    return new Input(List.copyOf(usable), quantum, (int) units);
  }

  private record Table(List<Denomination> denominations, long quantum, int[] count, int[] choice) {}

  private static Table table(List<Denomination> denominations, int amount, long quantum) {
    var counts = new int[amount + 1];
    var choices = new int[amount + 1];
    Arrays.fill(counts, UNREACHABLE);
    Arrays.fill(choices, -1);
    counts[0] = 0;
    for (int value = 1; value <= amount; value++) {
      for (int index = 0; index < denominations.size(); index++) {
        long step = denominations.get(index).minorUnits() / quantum;
        if (step > value) continue;
        int previous = counts[value - (int) step];
        if (previous < MAX_ITEMS && previous + 1 < counts[value]) {
          counts[value] = previous + 1;
          choices[value] = index;
        }
      }
    }
    return new Table(denominations, quantum, counts, choices);
  }

  private static Map<String, Integer> reconstruct(Table table, int amount) {
    var counts = new TreeMap<String, Integer>();
    while (amount > 0) {
      int choice = table.choice()[amount];
      if (choice < 0) throw failure("WITHDRAW_IMPOSSIBLE");
      var denomination = table.denominations().get(choice);
      counts.merge(denomination.id(), 1, Math::addExact);
      amount -= (int) (denomination.minorUnits() / table.quantum());
    }
    return counts;
  }

  private static Plan result(
      EconomyCurrency currency,
      long amount,
      List<Denomination> denominations,
      Map<String, Integer> bills,
      Map<String, Integer> coins) {
    var lines = new ArrayList<Line>();
    int count = 0;
    long sum = 0;
    for (var denomination : denominations) {
      int quantity = (denomination.bill() ? bills : coins).getOrDefault(denomination.id(), 0);
      if (quantity == 0) continue;
      count = Math.addExact(count, quantity);
      sum = Math.addExact(sum, Math.multiplyExact(denomination.minorUnits(), quantity));
      lines.add(new Line(denomination, quantity));
    }
    if (sum != amount || count > MAX_ITEMS) throw failure("WITHDRAW_IMPOSSIBLE");
    return new Plan(currency, amount, bills, coins, lines, count);
  }

  private static long gcd(long first, long second) {
    while (second != 0) {
      long remainder = first % second;
      first = second;
      second = remainder;
    }
    return first;
  }

  private static IllegalArgumentException failure(String code) {
    return new IllegalArgumentException(code);
  }
}

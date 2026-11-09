package ca.emilybelnavis.mcmods.economy.simulation;

import ca.emilybelnavis.mcmods.economy.simulation.model.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Loaded once per server lifecycle; durable changes use future journal policies. */
public record SimulationConfig(
    boolean rates, boolean traders, Rational cadPerEmerald, MacroModel.Config model) {
  private static final Set<String> KEYS =
      Set.of(
          "rates",
          "traders",
          "cadPerEmerald",
          "dailyFloor",
          "capacityFactor",
          "primeWeights",
          "fxWeights");

  public SimulationConfig {
    Objects.requireNonNull(model);
    Objects.requireNonNull(cadPerEmerald);
    if (cadPerEmerald.compareTo(Rational.ZERO) <= 0
        || cadPerEmerald.compareTo(Rational.of(1_000_000)) > 0)
      throw new IllegalArgumentException("cadPerEmerald must be >0 and <=1000000");
  }

  public static SimulationConfig defaults() {
    return parse(new Properties());
  }

  public UUID sourceId() {
    return UUID.nameUUIDFromBytes(
        ("economy:emerald-price:v1|" + cadPerEmerald)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public int sourceRevision() {
    return 1;
  }

  public static SimulationConfig load(Path file) {
    try {
      if (!Files.exists(file)) return defaults();
      if (Files.size(file) > 16384)
        throw new IllegalArgumentException("Simulation configuration exceeds16KiB");
      var p = new Properties();
      try (var reader = Files.newBufferedReader(file)) {
        p.load(reader);
      }
      return parse(p);
    } catch (IOException e) {
      throw new IllegalArgumentException("Simulation configuration is unreadable", e);
    }
  }

  public static SimulationConfig parse(Properties p) {
    for (String key : p.stringPropertyNames())
      if (!KEYS.contains(key))
        throw new IllegalArgumentException("Unknown simulation setting: " + key);
    var floor = number(p.getProperty("dailyFloor", "1000"));
    var capacity = number(p.getProperty("capacityFactor", "0.01"));
    if (floor.compareTo(Rational.of(1_000_000_000)) > 0 || capacity.compareTo(Rational.ONE) > 0)
      throw new IllegalArgumentException("Simulation normalization out of range");
    var prime = weights(p.getProperty("primeWeights", "1/3,1/3,1/3"), 3);
    var fx = weights(p.getProperty("fxWeights", "1/2,1/2"), 2);
    var id =
        UUID.nameUUIDFromBytes(
            ("economy:macro-config:v1|" + floor + "|" + capacity + "|" + prime + "|" + fx)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new SimulationConfig(
        flag(p, "rates"),
        flag(p, "traders"),
        number(p.getProperty("cadPerEmerald", "1")),
        new MacroModel.Config(id, 1, floor, capacity, prime, fx));
  }

  private static boolean flag(Properties p, String key) {
    return switch (p.getProperty(key, "true").strip()) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new IllegalArgumentException(key + " must be true or false");
    };
  }

  public static Rational number(String text) {
    if (text == null || text.length() > 80)
      throw new IllegalArgumentException("Bounded rational required");
    String[] pair = text.strip().split("/", -1);
    if (pair.length == 1) return Rational.of(decimal(pair[0]));
    if (pair.length == 2)
      return Rational.of(decimal(pair[0])).divide(Rational.of(decimal(pair[1])));
    throw new IllegalArgumentException("Invalid rational");
  }

  private static java.math.BigDecimal decimal(String text) {
    var n = new java.math.BigDecimal(text);
    if (n.precision() > 34 || Math.abs((long) n.scale()) > 18)
      throw new IllegalArgumentException("Rational precision/scale limit");
    return n;
  }

  private static List<Rational> weights(String text, int n) {
    if (text.length() > 256) throw new IllegalArgumentException("Weight limit");
    var parts = text.split(",", -1);
    if (parts.length != n) throw new IllegalArgumentException("Weight count");
    return Arrays.stream(parts).map(SimulationConfig::number).toList();
  }
}

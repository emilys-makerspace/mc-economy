package ca.emilybelnavis.mcmods.economy.banking;

import ca.emilybelnavis.mcmods.economy.api.*;
import com.google.gson.*;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Deferred presentation only. Neither keys nor formatted amounts are canonical financial input. */
public final class UiText {
  private UiText() {}

  private static final char OPEN = '\u001e', CLOSE = '\u001f';
  private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([0-9]+)}");
  private static final Map<String, Map<String, String>> CATALOGS = new ConcurrentHashMap<>();
  public static final int MAX_DEFERRED = 16_384;

  public static String t(String key, Object... arguments) {
    if (key == null || !key.matches("[a-z0-9_.]+") || key.length() > 128 || arguments.length > 16)
      throw new IllegalArgumentException("UI_MESSAGE_BOUNDS");
    var message = new JsonObject();
    message.addProperty("k", key);
    var args = new JsonArray();
    for (var argument : arguments) args.add(argument(argument));
    message.add("a", args);
    return token(message);
  }

  public static String money(Money money) {
    return token(argument(Objects.requireNonNull(money)));
  }

  public static String decimal(BigDecimal value) {
    return token(argument(Objects.requireNonNull(value)));
  }

  public static boolean isDeferred(String value) {
    return value != null && value.indexOf(OPEN) >= 0;
  }

  public static String language(ServerPlayer player) {
    return normalize(player.clientInformation().language());
  }

  public static Component component(ServerPlayer player, String value) {
    return Component.literal(render(value, language(player)));
  }

  public static Component component(
      net.minecraft.commands.CommandSourceStack source, String value) {
    return Component.literal(
        render(
            value, source.getEntity() instanceof ServerPlayer player ? language(player) : "en_gb"));
  }

  public static String render(String value, String language) {
    return render(value, normalize(language), 0);
  }

  private static JsonObject argument(Object value) {
    var arg = new JsonObject();
    if (value instanceof Boolean flag) {
      arg.addProperty("t", "b");
      arg.addProperty("v", flag.toString());
    } else if (value instanceof Money money) {
      arg.addProperty("t", "m");
      arg.addProperty("v", Long.toString(money.minorUnits()));
      arg.addProperty("c", money.currency().code());
    } else if (value instanceof BigDecimal decimal) {
      arg.addProperty("t", "d");
      arg.addProperty("v", decimal.toPlainString());
      arg.addProperty("s", decimal.scale());
    } else if (value instanceof Number number) {
      if (number instanceof Float || number instanceof Double) {
        double d = number.doubleValue();
        if (!Double.isFinite(d)) throw new IllegalArgumentException("UI_NUMBER");
        return argument(BigDecimal.valueOf(d));
      }
      arg.addProperty("t", "n");
      arg.addProperty("v", number.toString());
    } else {
      arg.addProperty("t", "s");
      arg.addProperty("v", value == null ? "" : value.toString());
    }
    if (arg.get("v").getAsString().length() > MAX_DEFERRED)
      throw new IllegalArgumentException("UI_ARGUMENT_BOUNDS");
    return arg;
  }

  private static String token(JsonObject message) {
    String text = OPEN + JSON.toJson(message) + CLOSE;
    if (text.length() > MAX_DEFERRED) throw new IllegalArgumentException("UI_MESSAGE_BOUNDS");
    return text;
  }

  private static String render(String value, String language, int depth) {
    Objects.requireNonNull(value);
    if (depth > 8 || value.length() > 65_536)
      throw new IllegalArgumentException("UI_MESSAGE_DEPTH");
    var out = new StringBuilder();
    int at = 0;
    while (at < value.length()) {
      int begin = value.indexOf(OPEN, at);
      if (begin < 0) {
        out.append(value, at, value.length());
        break;
      }
      out.append(value, at, begin);
      int end = value.indexOf(CLOSE, begin + 1);
      if (end < 0) throw new IllegalArgumentException("UI_MESSAGE_ENCODING");
      var message = JsonParser.parseString(value.substring(begin + 1, end)).getAsJsonObject();
      out.append(resolve(message, language, depth + 1));
      at = end + 1;
    }
    return out.toString();
  }

  private static String resolve(JsonObject message, String language, int depth) {
    if (!message.has("k")) return display(message, language, depth);
    String key = message.get("k").getAsString();
    String template = catalog(language).get(key);
    if (template == null) template = catalog("en_gb").get(key);
    if (template == null) template = catalog("en_us").get(key);
    if (template == null) throw new IllegalArgumentException("Missing UI message key: " + key);
    var args = message.getAsJsonArray("a");
    if (args.size() > 16) throw new IllegalArgumentException("UI_ARGUMENT_BOUNDS");
    var matcher = PLACEHOLDER.matcher(template);
    var out = new StringBuilder();
    while (matcher.find()) {
      int index = Integer.parseInt(matcher.group(1));
      if (index >= args.size()) throw new IllegalArgumentException("UI_ARGUMENT_MISSING");
      matcher.appendReplacement(
          out,
          java.util.regex.Matcher.quoteReplacement(
              display(args.get(index).getAsJsonObject(), language, depth)));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static String display(JsonObject argument, String language, int depth) {
    String kind = argument.get("t").getAsString(), value = argument.get("v").getAsString();
    var locale = Locale.forLanguageTag(language.replace('_', '-'));
    if (kind.equals("s")) return render(value, language, depth + 1);
    if (kind.equals("b"))
      return render(
          t(value.equals("true") ? "ui.common.yes" : "ui.common.no"), language, depth + 1);
    var number = NumberFormat.getNumberInstance(locale);
    number.setGroupingUsed(true);
    if (kind.equals("m")) {
      var currency = EconomyCurrency.fromCode(argument.get("c").getAsString());
      int scale = currency.fractionDigits();
      number.setMinimumFractionDigits(scale);
      number.setMaximumFractionDigits(scale);
      return number.format(BigDecimal.valueOf(Long.parseLong(value), scale))
          + " "
          + currency.code();
    }
    if (kind.equals("d")) {
      int scale = Math.max(0, argument.get("s").getAsInt());
      if (scale > 64) throw new IllegalArgumentException("UI_DECIMAL_SCALE");
      number.setMinimumFractionDigits(scale);
      number.setMaximumFractionDigits(scale);
      return number.format(new BigDecimal(value));
    }
    if (!kind.equals("n")) throw new IllegalArgumentException("UI_ARGUMENT_TYPE");
    number.setMaximumFractionDigits(0);
    return number.format(new BigInteger(value));
  }

  /** Diagnostic redaction may map literal text, but never translation keys or typed money. */
  public static String mapText(String value, UnaryOperator<String> mapper) {
    return mapText(value, mapper, 0);
  }

  private static String mapText(String value, UnaryOperator<String> mapper, int depth) {
    if (depth > 8) throw new IllegalArgumentException("UI_MESSAGE_DEPTH");
    var out = new StringBuilder();
    int at = 0;
    while (at < value.length()) {
      int begin = value.indexOf(OPEN, at);
      if (begin < 0) {
        out.append(mapper.apply(value.substring(at)));
        break;
      }
      if (begin > at) out.append(mapper.apply(value.substring(at, begin)));
      int end = value.indexOf(CLOSE, begin + 1);
      if (end < 0) throw new IllegalArgumentException("UI_MESSAGE_ENCODING");
      var object = JsonParser.parseString(value.substring(begin + 1, end)).getAsJsonObject();
      if (object.has("k")) {
        for (var a : object.getAsJsonArray("a")) {
          var arg = a.getAsJsonObject();
          if (arg.get("t").getAsString().equals("s"))
            arg.addProperty("v", mapText(arg.get("v").getAsString(), mapper, depth + 1));
        }
      } else if (object.get("t").getAsString().equals("s"))
        object.addProperty("v", mapText(object.get("v").getAsString(), mapper, depth + 1));
      out.append(token(object));
      at = end + 1;
    }
    return out.toString();
  }

  private static String normalize(String language) {
    return language != null
            && Set.of("en_gb", "fr_ca", "fr_fr").contains(language.toLowerCase(Locale.ROOT))
        ? language.toLowerCase(Locale.ROOT)
        : "en_us";
  }

  private static Map<String, String> catalog(String language) {
    return CATALOGS.computeIfAbsent(
        language,
        name -> {
          String path = "/assets/economy/lang/" + name + ".json";
          try (var stream = UiText.class.getResourceAsStream(path)) {
            if (stream == null)
              throw new IllegalStateException("Missing language resource " + name);
            var object =
                JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            var entries = new HashMap<String, String>();
            for (var entry : object.entrySet())
              entries.put(entry.getKey(), entry.getValue().getAsString());
            return Map.copyOf(entries);
          } catch (IOException e) {
            throw new IllegalStateException("Cannot read UI language " + name, e);
          }
        });
  }
}

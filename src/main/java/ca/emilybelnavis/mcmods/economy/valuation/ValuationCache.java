package ca.emilybelnavis.mcmods.economy.valuation;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Session-owned bounded detached caches. Eviction never deletes retained storage. */
final class ValuationCache {
    private UUID active;
    private final Map<UUID, ValuationData.Manifest> compiled = new LinkedHashMap<>(16, .75f, true);
    private final Map<Key, RecipeValues.Result> results = new LinkedHashMap<>(16, .75f, true);
    private record Key(UUID snapshot, UUID fx, String item, String components, long quantity, EconomyCurrency currency) {}

    synchronized void activate(ValuationStore.Snapshot snapshot, ValuationData.Manifest manifest) {
        active = snapshot.id();
        compiled.put(snapshot.id(), manifest);
        trim();
        results.clear();
    }
    ValuationData.Manifest retain(ValuationStore.Snapshot snapshot) {
        synchronized (this) {
            var cached = compiled.get(snapshot.id());
            if (cached != null) return cached;
        }
        // Historical reconstruction runs on the storage worker without holding the cache monitor.
        var decoded = ValuationData.decode(snapshot.payload());
        synchronized (this) {
            var manifest = compiled.putIfAbsent(snapshot.id(), decoded);
            trim();
            return manifest == null ? decoded : manifest;
        }
    }
    private void trim() {
        // One active plus no more than sixteen historical compiled snapshots.
        while (compiled.size() > 17) {
            var it = compiled.keySet().iterator();
            while (it.hasNext()) { if (!it.next().equals(active)) { it.remove(); break; } }
        }
    }
    synchronized RecipeValues.Result cached(UUID snapshot, String item, String components, long quantity,
                                           EconomyCurrency currency, UUID fx) {
        return results.get(new Key(snapshot, fx, item, components, quantity, currency));
    }
    RecipeValues.Result evaluate(ValuationStore.Snapshot snapshot, String item, String components,
                               long quantity, EconomyCurrency currency, EffectiveFxSnapshot fx) {
        var key = new Key(snapshot.id(), fx == null ? null : fx.id(), item, components, quantity, currency);
        synchronized (this) {
            var cached = results.get(key);
            if (cached != null) return cached;
        }
        var evaluated = RecipeValues.evaluate(retain(snapshot), item, components, quantity, currency, fx);
        synchronized (this) {
            var previous = results.putIfAbsent(key, evaluated);
            if (results.size() > 10_000) results.remove(results.keySet().iterator().next());
            return previous == null ? evaluated : previous;
        }
    }
}

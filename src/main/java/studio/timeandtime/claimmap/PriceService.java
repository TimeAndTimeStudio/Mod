package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Central price table (PHASES.md phase 4): refreshes from JSONBin in the
 * background, keeps the last good table when the network fails, and lets an
 * admin override or block items. Prices are integers ("full coins" per item).
 *
 * <p>Does not depend on Minecraft classes apart from the registry check done by
 * the web layer, so it is unit-testable.
 */
public final class PriceService {
	/** Buy/sell pair for one item. */
	public record PricePair(double buy, double sell) {
	}

	public record PriceHistoryPoint(long t, double price, int sold) {
	}

	public record ItemPriceDetail(String id, long nominalBase, double effectiveBase,
			double buyPrice, double sellPrice, double multiplier, int soldUnits,
			boolean blocked, boolean overridden) {
	}

	/** Parses one price cell: integer coins per item, rounded HALF_UP, bounds-checked. */
	private static Long baseFrom(JsonElement v) {
		if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return null;
		try {
			BigDecimal bd = new BigDecimal(v.getAsString());
			long base = bd.setScale(0, RoundingMode.HALF_UP).longValueExact();
			if (base < 1 || base > EconomyManager.MAX_MONEY) return null;
			return base;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static final long STALE_AFTER_MS = 60L * 60_000L;      // 1 hour
	private static final long WARN_EVERY_MS = 10L * 60_000L;      // 10 minutes
	private static final Gson GSON = new GsonBuilder().create();

	private final Path dir;
	private final Path pricesFile;
	private final Path cacheFile;
	private final Path overridesFile;
	private final Path dynamicsFile;
	private final ClaimMapConfig cfg;
	private final LongSupplier now;
	private final HttpClient http;

	private final Map<String, Integer> soldUnits = new ConcurrentHashMap<>();
	private final Map<String, Long> lastDecayTimes = new ConcurrentHashMap<>();
	private final Map<String, List<PriceHistoryPoint>> history = new ConcurrentHashMap<>();

	private volatile Map<String, Long> webPrices = Map.of();
	private volatile Map<String, Long> overrides = Map.of();
	private volatile Set<String> fileBlocked = Set.of();
	private volatile long fetchedAt;
	private volatile long lastSuccessMs;
	private volatile boolean stale;
	private volatile long lastWarnMs;
	private volatile boolean loggedMissingConfig;
	private ScheduledFuture<?> task;
	private ScheduledFuture<?> recoveryTask;
	private final Set<String> configBlocked = new HashSet<>();
	private volatile Set<String> fileUnblocked = Set.of();

	public PriceService(Path dir, ClaimMapConfig cfg, LongSupplier now) {
		this.dir = dir;
		this.pricesFile = dir.resolve("prices.json");
		this.cacheFile = dir.resolve("prices-cache.json");
		this.overridesFile = dir.resolve("price-overrides.json");
		this.dynamicsFile = dir.resolve("price-dynamics.json");
		this.cfg = cfg;
		this.now = now;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		for (String id : ClaimMapConfig.parseNames(cfg.blockedItems)) {
			configBlocked.add(normalize(id));
		}
	}

	// ---- loading ------------------------------------------------------------

	public void loadCache() {
		synchronized (Market.LOCK) {
			readCacheFile();
			readOverridesFile();
			readDynamicsFile();
		}
		ClaimMap.LOGGER.info("[timesmp] Prices loaded: {} base entries, {} overrides, {} blocked",
				webPrices.size(), overrides.size(), blockedCount());
	}

	private void readCacheFile() {
		Path target = Files.exists(pricesFile) ? pricesFile : cacheFile;
		if (!Files.exists(target)) {
			target = dir.resolve("default-prices.json");
		}
		if (!Files.exists(target)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(target, StandardCharsets.UTF_8)).getAsJsonObject();
			fetchedAt = root.has("fetchedAt") ? root.get("fetchedAt").getAsLong() : 0L;
			Map<String, Long> parsed = new HashMap<>();
			JsonObject prices = root.getAsJsonObject("prices");
			if (prices != null) {
				for (Map.Entry<String, JsonElement> e : prices.entrySet()) {
					Long v = baseFrom(e.getValue());
					if (v != null) parsed.put(normalize(e.getKey()), v);
				}
			}
			webPrices = Map.copyOf(parsed);
			lastSuccessMs = fetchedAt;
		} catch (Exception e) {
			// Prices are not money: keep running with no table instead of disabling the market.
			ClaimMap.LOGGER.warn("[timesmp] Could not read prices from {} ({}); starting without base prices", target.getFileName(), e.getMessage());
			webPrices = Map.of();
			fetchedAt = 0;
		}
	}

	private void readOverridesFile() {
		if (!Files.exists(overridesFile)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(overridesFile, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<String, Long> o = new HashMap<>();
			JsonObject ov = root.getAsJsonObject("overrides");
			if (ov != null) {
				for (Map.Entry<String, JsonElement> e : ov.entrySet()) {
					Long v = baseFrom(e.getValue());
					if (v != null) o.put(normalize(e.getKey()), v);
				}
			}
			overrides = Map.copyOf(o);
			fileBlocked = readIdSet(root.getAsJsonArray("blocked"));
			fileUnblocked = readIdSet(root.getAsJsonArray("unblocked"));
		} catch (Exception e) {
			ClaimMap.LOGGER.warn("[timesmp] Could not read price-overrides.json ({})", e.getMessage());
			overrides = Map.of();
			fileBlocked = Set.of();
			fileUnblocked = Set.of();
		}
	}

	private static Set<String> readIdSet(JsonArray arr) {
		if (arr == null) return Set.of();
		Set<String> out = new HashSet<>();
		for (JsonElement e : arr) {
			if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
				out.add(normalize(e.getAsString()));
			}
		}
		return Set.copyOf(out);
	}

	private void readDynamicsFile() {
		if (!Files.exists(dynamicsFile)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(dynamicsFile, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.has("sold") && root.get("sold").isJsonObject()) {
				JsonObject soldObj = root.getAsJsonObject("sold");
				for (Map.Entry<String, JsonElement> e : soldObj.entrySet()) {
					if (e.getValue().isJsonPrimitive()) {
						soldUnits.put(normalize(e.getKey()), e.getValue().getAsInt());
					}
				}
			}
			if (root.has("lastDecay") && root.get("lastDecay").isJsonObject()) {
				JsonObject decObj = root.getAsJsonObject("lastDecay");
				for (Map.Entry<String, JsonElement> e : decObj.entrySet()) {
					if (e.getValue().isJsonPrimitive()) {
						lastDecayTimes.put(normalize(e.getKey()), e.getValue().getAsLong());
					}
				}
			}
			if (root.has("history") && root.get("history").isJsonObject()) {
				JsonObject histObj = root.getAsJsonObject("history");
				for (Map.Entry<String, JsonElement> e : histObj.entrySet()) {
					if (e.getValue().isJsonArray()) {
						List<PriceHistoryPoint> points = new CopyOnWriteArrayList<>();
						for (JsonElement item : e.getValue().getAsJsonArray()) {
							if (item.isJsonObject()) {
								JsonObject p = item.getAsJsonObject();
								long t = p.has("t") ? p.get("t").getAsLong() : 0L;
								double pr = p.has("price") ? p.get("price").getAsDouble() : 0.0;
								int s = p.has("sold") ? p.get("sold").getAsInt() : 0;
								points.add(new PriceHistoryPoint(t, pr, s));
							}
						}
						history.put(normalize(e.getKey()), points);
					}
				}
			}
		} catch (Exception e) {
			ClaimMap.LOGGER.warn("[timesmp] Could not read price-dynamics.json ({})", e.getMessage());
		}
	}

	public void writeDynamicsFile() {
		JsonObject root = new JsonObject();
		JsonObject soldObj = new JsonObject();
		for (Map.Entry<String, Integer> e : soldUnits.entrySet()) {
			soldObj.addProperty(e.getKey(), e.getValue());
		}
		root.add("sold", soldObj);
		JsonObject decObj = new JsonObject();
		for (Map.Entry<String, Long> e : lastDecayTimes.entrySet()) {
			decObj.addProperty(e.getKey(), e.getValue());
		}
		root.add("lastDecay", decObj);
		JsonObject histObj = new JsonObject();
		for (Map.Entry<String, List<PriceHistoryPoint>> e : history.entrySet()) {
			JsonArray arr = new JsonArray();
			for (PriceHistoryPoint p : e.getValue()) {
				JsonObject po = new JsonObject();
				po.addProperty("t", p.t());
				po.addProperty("price", Math.round(p.price() * 100.0) / 100.0);
				po.addProperty("sold", p.sold());
				arr.add(po);
			}
			histObj.add(e.getKey(), arr);
		}
		root.add("history", histObj);
		try {
			Path parent = dynamicsFile.getParent();
			if (parent != null) Files.createDirectories(parent);
			Path tmp = dynamicsFile.resolveSibling(dynamicsFile.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, dynamicsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[timesmp] Could not write price-dynamics.json", e);
		}
	}

	// ---- refresh ------------------------------------------------------------

	public void start(ScheduledExecutorService ex) {
		recoveryTask = ex.scheduleWithFixedDelay(() -> {
			try {
				applyAllDecay(now.getAsLong());
			} catch (Throwable t) {
				ClaimMap.LOGGER.warn("[timesmp] Price recovery tick failed: {}", t.toString());
			}
		}, 30, 30, TimeUnit.SECONDS);

		if (cfg.pricesUrl.isBlank() || cfg.pricesAccessKey.isBlank()) {
			if (!loggedMissingConfig) {
				loggedMissingConfig = true;
				ClaimMap.LOGGER.info("[timesmp] pricesUrl/pricesAccessKey not set — using prices-cache.json only");
			}
			return;
		}
		long seconds = Math.max(5, cfg.priceRefreshSeconds);
		task = ex.scheduleWithFixedDelay(this::refreshQuiet, 0, seconds, TimeUnit.SECONDS);
	}

	public void stop() {
		ScheduledFuture<?> t = task;
		if (t != null) t.cancel(false);
		ScheduledFuture<?> r = recoveryTask;
		if (r != null) r.cancel(false);
	}

	private void refreshQuiet() {
		try {
			refreshNow();
		} catch (Throwable t) {
			ClaimMap.LOGGER.warn("[timesmp] Price refresh failed: {}", t.toString());
		}
	}

	/** Blocking refresh. True when a usable table was fetched and stored. */
	public boolean refreshNow() {
		if (cfg.pricesUrl.isBlank() || cfg.pricesAccessKey.isBlank()) {
			return false;
		}
		String body;
		int status;
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.pricesUrl.trim()))
					.timeout(Duration.ofSeconds(10))
					.header("X-Access-Key", cfg.pricesAccessKey.trim())
					.header("X-Bin-Meta", "false")
					.header("Accept", "application/json")
					.GET()
					.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			status = response.statusCode();
			body = response.body();
		} catch (Exception e) {
			markFailure("network error: " + e.getClass().getSimpleName());
			return false;
		}
		if (status != 200) {
			markFailure("HTTP " + status);
			return false;
		}
		Optional<Map<String, Long>> parsed = parsePayload(body);
		if (parsed.isEmpty()) {
			markFailure("response had no usable prices");
			return false;
		}
		return applyFetched(parsed.get());
	}

	/** Applies a fetched table; package-private so tests can skip the network. */
	boolean applyFetched(Map<String, Long> prices) {
		if (prices == null || prices.isEmpty()) return false;
		Map<String, Long> clean = new HashMap<>();
		for (Map.Entry<String, Long> e : prices.entrySet()) {
			Long v = e.getValue();
			if (v == null || v < 1 || v > EconomyManager.MAX_MONEY) continue;
			clean.put(normalize(e.getKey()), v);
		}
		if (clean.isEmpty()) return false;
		synchronized (Market.LOCK) {
			webPrices = Map.copyOf(clean);
			fetchedAt = now.getAsLong();
			lastSuccessMs = fetchedAt;
			stale = false;
			writeCacheFile();
		}
		return true;
	}

	void markFailure(String reason) {
		stale = true;
		long nowMs = now.getAsLong();
		if (nowMs - lastWarnMs >= WARN_EVERY_MS) {
			lastWarnMs = nowMs;
			ClaimMap.LOGGER.warn("[timesmp] Price refresh failed ({}); keeping the last good table ({} entries)",
					reason, webPrices.size());
		}
	}

	/** Parses the JSONBin payload ({@code {"prices":{...}}}, also accepts a wrapper {@code record}). */
	static Optional<Map<String, Long>> parsePayload(String body) {
		if (body == null || body.isBlank()) return Optional.empty();
		JsonObject root;
		try {
			JsonElement el = JsonParser.parseString(body);
			if (!el.isJsonObject()) return Optional.empty();
			root = el.getAsJsonObject();
		} catch (RuntimeException e) {
			return Optional.empty();
		}
		JsonObject prices = null;
		if (root.has("prices") && root.get("prices").isJsonObject()) {
			prices = root.getAsJsonObject("prices");
		} else if (root.has("record") && root.get("record").isJsonObject()) {
			JsonObject record = root.getAsJsonObject("record");
			if (record.has("prices") && record.get("prices").isJsonObject()) {
				prices = record.getAsJsonObject("prices");
			}
		}
		if (prices == null) return Optional.empty();

		Map<String, Long> out = new HashMap<>();
		int skipped = 0;
		for (Map.Entry<String, JsonElement> e : prices.entrySet()) {
			Long base = baseFrom(e.getValue());
			if (base == null) {
				skipped++;
				continue;
			}
			out.put(normalize(e.getKey()), base);
		}
		if (skipped > 0) {
			ClaimMap.LOGGER.warn("[timesmp] Price payload: skipped {} unusable entries", skipped);
		}
		return out.isEmpty() ? Optional.empty() : Optional.of(out);
	}

	// ---- queries ------------------------------------------------------------

	public synchronized boolean applyDecay(String id, long nowMs) {
		int current = soldUnits.getOrDefault(id, 0);
		if (current <= 0) return false;
		long last = lastDecayTimes.getOrDefault(id, nowMs);
		if (nowMs <= last) return false;
		long elapsedMinutes = (nowMs - last) / 60_000L;
		if (elapsedMinutes < 1) return false;

		// Decay rate: ~2% per minute of inactive supply, at least 1 unit per minute
		int decay = (int) Math.max(1, Math.ceil(current * 0.02 * elapsedMinutes));
		int next = Math.max(0, current - decay);
		soldUnits.put(id, next);
		lastDecayTimes.put(id, nowMs);

		// Record price recovery in history point so chart displays the rebound
		List<PriceHistoryPoint> list = history.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
		double curSell = sellPriceDouble(id);
		list.add(new PriceHistoryPoint(nowMs, curSell, 0));
		while (list.size() > 50) {
			list.remove(0);
		}
		return true;
	}

	public synchronized void applyAllDecay(long nowMs) {
		boolean changed = false;
		for (String id : soldUnits.keySet()) {
			if (applyDecay(id, nowMs)) {
				changed = true;
			}
		}
		if (changed) {
			writeDynamicsFile();
		}
	}

	public void recordSale(String rawItem, int qty) {
		String id = normalize(rawItem);
		if (id.isEmpty() || qty <= 0) return;
		long currentNow = now.getAsLong();
		applyDecay(id, currentNow);
		soldUnits.merge(id, qty, Integer::sum);
		lastDecayTimes.put(id, currentNow);
		double curSell = sellPriceDouble(id);
		List<PriceHistoryPoint> list = history.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
		list.add(new PriceHistoryPoint(currentNow, curSell, qty));
		while (list.size() > 50) {
			list.remove(0);
		}
		writeDynamicsFile();
	}

	public void recordBuy(String rawItem, int qty) {
		String id = normalize(rawItem);
		if (id.isEmpty() || qty <= 0) return;
		long currentNow = now.getAsLong();
		applyDecay(id, currentNow);
		soldUnits.compute(id, (k, v) -> v == null ? 0 : Math.max(0, v - qty));
		lastDecayTimes.put(id, currentNow);
		writeDynamicsFile();
	}

	public double multiplier(String rawItem) {
		String id = normalize(rawItem);
		applyDecay(id, now.getAsLong());
		int sold = soldUnits.getOrDefault(id, 0);
		if (sold <= 0) return 1.0;
		// Smooth saturation curve: e.g. at 100 sold ~87%, at 500 sold ~57%, at 1000 sold ~40%, minimum floor 0.20 (20%)
		return Math.max(0.20, 1.0 / (1.0 + (sold * 0.0015)));
	}

	public int getSoldUnits(String rawItem) {
		String id = normalize(rawItem);
		applyDecay(id, now.getAsLong());
		return soldUnits.getOrDefault(normalize(rawItem), 0);
	}

	public List<PriceHistoryPoint> getHistory(String rawItem) {
		List<PriceHistoryPoint> list = history.get(normalize(rawItem));
		return list == null ? List.of() : List.copyOf(list);
	}

	public OptionalLong nominalBase(String itemId) {
		String id = normalize(itemId);
		Long o = overrides.get(id);
		if (o != null) return OptionalLong.of(o);
		Long w = webPrices.get(id);
		return w == null ? OptionalLong.empty() : OptionalLong.of(w);
	}

	public OptionalLong base(String itemId) {
		OptionalLong nom = nominalBase(itemId);
		if (nom.isEmpty()) return OptionalLong.empty();
		double mult = multiplier(itemId);
		long eff = Math.max(1L, Math.round(nom.getAsLong() * mult));
		return OptionalLong.of(eff);
	}

	public double sellPriceDouble(String itemId) {
		if (isBlocked(itemId)) return 0.0;
		OptionalLong nom = nominalBase(itemId);
		if (nom.isEmpty()) return 0.0;
		double mult = multiplier(itemId);
		long b = nom.getAsLong();
		double nominalSell = (b <= 1L) ? 1.0 : (b * (double) cfg.sellPercent / 100.0);
		double raw = nominalSell * mult;
		return Math.max(0.01, Math.round(raw * 100.0) / 100.0);
	}

	public double buyPriceDouble(String itemId) {
		if (isBlocked(itemId)) return 0.0;
		OptionalLong nom = nominalBase(itemId);
		if (nom.isEmpty()) return 0.0;
		double mult = multiplier(itemId);
		long b = nom.getAsLong();
		double raw = (b * (double) cfg.buyPercent / 100.0) * mult;
		double sell = sellPriceDouble(itemId);
		return Math.max(sell + 0.01, Math.round(raw * 100.0) / 100.0);
	}

	public OptionalLong buyPrice(String itemId) {
		if (isBlocked(itemId)) return OptionalLong.empty();
		double bp = buyPriceDouble(itemId);
		if (bp <= 0.0) return OptionalLong.empty();
		return OptionalLong.of(Math.max(1L, Math.round(bp)));
	}

	public OptionalLong sellPrice(String itemId) {
		if (isBlocked(itemId)) return OptionalLong.empty();
		double sp = sellPriceDouble(itemId);
		if (sp <= 0.0) return OptionalLong.empty();
		return OptionalLong.of(Math.max(1L, Math.round(sp)));
	}

	public boolean isBlocked(String itemId) {
		String id = normalize(itemId);
		if (fileBlocked.contains(id)) return true;
		return configBlocked.contains(id) && !fileUnblocked.contains(id);
	}

	/** Immutable copy of every priced, non-blocked item (registry filtering happens at the web layer). */
	public Map<String, PricePair> snapshot() {
		Map<String, Long> ov = overrides;
		Map<String, Long> web = webPrices;
		TreeSet<String> keys = new TreeSet<>();
		keys.addAll(web.keySet());
		keys.addAll(ov.keySet());
		Map<String, PricePair> out = new LinkedHashMap<>();
		for (String id : keys) {
			if (isBlocked(id)) continue;
			double buy = buyPriceDouble(id);
			double sell = sellPriceDouble(id);
			if (sell <= 0.0) continue;
			out.put(id, new PricePair(buy, sell));
		}
		return out;
	}

	public List<ItemPriceDetail> detailsSnapshot() {
		Map<String, Long> ov = overrides;
		Map<String, Long> web = webPrices;
		TreeSet<String> keys = new TreeSet<>();
		keys.addAll(web.keySet());
		keys.addAll(ov.keySet());
		List<ItemPriceDetail> out = new ArrayList<>();
		for (String id : keys) {
			boolean blocked = isBlocked(id);
			boolean overridden = ov.containsKey(id);
			long nom = ov.getOrDefault(id, web.getOrDefault(id, 0L));
			if (nom < 1) continue;
			double mult = multiplier(id);
			double eff = Math.round(nom * mult * 100.0) / 100.0;
			double buy = buyPriceDouble(id);
			double sell = sellPriceDouble(id);
			int sold = soldUnits.getOrDefault(id, 0);
			out.add(new ItemPriceDetail(id, nom, eff, buy, sell, mult, sold, blocked, overridden));
		}
		return out;
	}

	public long lastSuccessMs() {
		return lastSuccessMs;
	}

	/** True when the newest refresh attempt failed. */
	public boolean usingStaleCache() {
		return stale;
	}

	/** Stale flag shown to the UI: nothing successful for over an hour. */
	public boolean staleForApi() {
		long last = lastSuccessMs;
		if (last == 0) return webPrices.isEmpty() ? false : true;
		return now.getAsLong() - last > STALE_AFTER_MS;
	}

	public long updatedAt() {
		return fetchedAt;
	}

	// ---- admin mutations ----------------------------------------------------

	public void setOverride(String itemId, Long baseOrNull) {
		String id = normalize(itemId);
		synchronized (Market.LOCK) {
			Map<String, Long> copy = new HashMap<>(overrides);
			if (baseOrNull == null) {
				copy.remove(id);
			} else {
				long v = Math.max(1L, Math.min(EconomyManager.MAX_MONEY, baseOrNull));
				copy.put(id, v);
			}
			overrides = Map.copyOf(copy);
			writeOverridesFile();
		}
	}

	public void setBlocked(String itemId, boolean blocked) {
		String id = normalize(itemId);
		synchronized (Market.LOCK) {
			Set<String> blockedIds = new HashSet<>(fileBlocked);
			Set<String> unblockedIds = new HashSet<>(fileUnblocked);
			if (blocked) {
				blockedIds.add(id);
				unblockedIds.remove(id);
			} else {
				blockedIds.remove(id);
				// Let an admin unblock an item that config ``blockedItems`` lists,
				// without rewriting claimmap.properties (PHASES.md 7.3).
				if (configBlocked.contains(id)) unblockedIds.add(id);
				else unblockedIds.remove(id);
			}
			fileBlocked = Set.copyOf(blockedIds);
			fileUnblocked = Set.copyOf(unblockedIds);
			writeOverridesFile();
		}
	}

	public Map<String, Long> overridesView() {
		return Map.copyOf(overrides);
	}

	public Set<String> blockedView() {
		Set<String> all = new HashSet<>(configBlocked);
		all.removeAll(fileUnblocked);
		all.addAll(fileBlocked);
		return Set.copyOf(all);
	}

	private int blockedCount() {
		return blockedView().size();
	}

	// ---- files --------------------------------------------------------------

	private void writeCacheFile() {
		try {
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			root.addProperty("fetchedAt", fetchedAt);
			JsonObject prices = new JsonObject();
			for (Map.Entry<String, Long> e : webPrices.entrySet()) prices.addProperty(e.getKey(), e.getValue());
			root.add("prices", prices);
			String json = GSON.toJson(root);
			writeAtomic(pricesFile, json);
			writeAtomic(cacheFile, json);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[timesmp] Could not write prices.json", e);
		}
	}

	private void writeOverridesFile() {
		try {
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			JsonObject ov = new JsonObject();
			for (Map.Entry<String, Long> e : overrides.entrySet()) ov.addProperty(e.getKey(), e.getValue());
			root.add("overrides", ov);
			JsonArray blocked = new JsonArray();
			for (String id : new TreeSet<>(fileBlocked)) blocked.add(id);
			root.add("blocked", blocked);
			JsonArray unblocked = new JsonArray();
			for (String id : new TreeSet<>(fileUnblocked)) unblocked.add(id);
			root.add("unblocked", unblocked);
			writeAtomic(overridesFile, GSON.toJson(root));
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[timesmp] Could not write price-overrides.json", e);
		}
	}

	private void writeAtomic(Path target, String json) throws IOException {
		Path parent = target.getParent();
		if (parent != null) Files.createDirectories(parent);
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(tmp, json, StandardCharsets.UTF_8);
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Adds/keeps the {@code minecraft:} namespace and lowercases. */
	public static String normalize(String itemId) {
		if (itemId == null) return "";
		String id = itemId.trim().toLowerCase(Locale.ROOT);
		if (id.isEmpty()) return "";
		if (id.indexOf(':') < 0) return "minecraft:" + id;
		return id;
	}

	/** Sorted list helper for the admin UI. */
	static List<String> sorted(Set<String> ids) {
		List<String> out = new ArrayList<>(ids);
		out.sort(Comparator.naturalOrder());
		return out;
	}
}

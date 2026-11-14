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
	public record PricePair(long buy, long sell) {
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
	private final Path cacheFile;
	private final Path overridesFile;
	private final ClaimMapConfig cfg;
	private final LongSupplier now;
	private final HttpClient http;

	private volatile Map<String, Long> webPrices = Map.of();
	private volatile Map<String, Long> overrides = Map.of();
	private volatile Set<String> fileBlocked = Set.of();
	private volatile long fetchedAt;
	private volatile long lastSuccessMs;
	private volatile boolean stale;
	private volatile long lastWarnMs;
	private volatile boolean loggedMissingConfig;
	private ScheduledFuture<?> task;
	private final Set<String> configBlocked = new HashSet<>();

	public PriceService(Path dir, ClaimMapConfig cfg, LongSupplier now) {
		this.dir = dir;
		this.cacheFile = dir.resolve("prices-cache.json");
		this.overridesFile = dir.resolve("price-overrides.json");
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
		}
		ClaimMap.LOGGER.info("[claimmap] Prices loaded: {} web entries, {} overrides, {} blocked",
				webPrices.size(), overrides.size(), blockedCount());
	}

	private void readCacheFile() {
		if (!Files.exists(cacheFile)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(cacheFile, StandardCharsets.UTF_8)).getAsJsonObject();
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
			ClaimMap.LOGGER.warn("[claimmap] Could not read prices-cache.json ({}); starting without cached prices", e.getMessage());
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
			Set<String> blocked = new HashSet<>();
			JsonArray arr = root.getAsJsonArray("blocked");
			if (arr != null) {
				for (JsonElement e : arr) {
					if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
						blocked.add(normalize(e.getAsString()));
					}
				}
			}
			fileBlocked = Set.copyOf(blocked);
		} catch (Exception e) {
			ClaimMap.LOGGER.warn("[claimmap] Could not read price-overrides.json ({})", e.getMessage());
			overrides = Map.of();
			fileBlocked = Set.of();
		}
	}

	// ---- refresh ------------------------------------------------------------

	public void start(ScheduledExecutorService ex) {
		if (cfg.pricesUrl.isBlank() || cfg.pricesAccessKey.isBlank()) {
			if (!loggedMissingConfig) {
				loggedMissingConfig = true;
				ClaimMap.LOGGER.info("[claimmap] pricesUrl/pricesAccessKey not set — using prices-cache.json only");
			}
			return;
		}
		long seconds = Math.max(5, cfg.priceRefreshSeconds);
		task = ex.scheduleWithFixedDelay(this::refreshQuiet, 0, seconds, TimeUnit.SECONDS);
	}

	public void stop() {
		ScheduledFuture<?> t = task;
		if (t != null) t.cancel(false);
	}

	private void refreshQuiet() {
		try {
			refreshNow();
		} catch (Throwable t) {
			ClaimMap.LOGGER.warn("[claimmap] Price refresh failed: {}", t.toString());
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
			ClaimMap.LOGGER.warn("[claimmap] Price refresh failed ({}); keeping the last good table ({} entries)",
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
			ClaimMap.LOGGER.warn("[claimmap] Price payload: skipped {} unusable entries", skipped);
		}
		return out.isEmpty() ? Optional.empty() : Optional.of(out);
	}

	// ---- queries ------------------------------------------------------------

	public OptionalLong base(String itemId) {
		String id = normalize(itemId);
		Long o = overrides.get(id);
		if (o != null) return OptionalLong.of(o);
		Long w = webPrices.get(id);
		return w == null ? OptionalLong.empty() : OptionalLong.of(w);
	}

	public OptionalLong buyPrice(String itemId) {
		if (isBlocked(itemId)) return OptionalLong.empty();
		OptionalLong b = base(itemId);
		return b.isEmpty() ? OptionalLong.empty()
				: OptionalLong.of(PriceMath.buyPrice(b.getAsLong(), cfg.sellPercent, cfg.buyPercent));
	}

	public OptionalLong sellPrice(String itemId) {
		if (isBlocked(itemId)) return OptionalLong.empty();
		OptionalLong b = base(itemId);
		return b.isEmpty() ? OptionalLong.empty()
				: OptionalLong.of(PriceMath.sellPrice(b.getAsLong(), cfg.sellPercent));
	}

	public boolean isBlocked(String itemId) {
		String id = normalize(itemId);
		return configBlocked.contains(id) || fileBlocked.contains(id);
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
			long base = ov.getOrDefault(id, web.getOrDefault(id, 0L));
			if (base < 1) continue;
			out.put(id, new PricePair(
					PriceMath.buyPrice(base, cfg.sellPercent, cfg.buyPercent),
					PriceMath.sellPrice(base, cfg.sellPercent)));
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
			Set<String> copy = new HashSet<>(fileBlocked);
			if (blocked) copy.add(id);
			else copy.remove(id);
			fileBlocked = Set.copyOf(copy);
			writeOverridesFile();
		}
	}

	public Map<String, Long> overridesView() {
		return Map.copyOf(overrides);
	}

	public Set<String> blockedView() {
		Set<String> all = new HashSet<>(configBlocked);
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
			writeAtomic(cacheFile, GSON.toJson(root));
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[claimmap] Could not write prices-cache.json", e);
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
			writeAtomic(overridesFile, GSON.toJson(root));
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[claimmap] Could not write price-overrides.json", e);
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

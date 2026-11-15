package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * Tracks player sales to the shop and market, persisting to {@code sales.jsonl}.
 * Used by admin audit, player sales history, and dynamic pricing.
 */
public final class SalesTracker {
	private static final Gson GSON = new GsonBuilder().create();
	private static final int MAX_IN_MEMORY = 2000;

	public record SaleRecord(
			String id,
			long t,
			String player,
			String uuid,
			String itemId,
			String label,
			int count,
			long earned,
			long unitPrice,
			String source
	) {}

	public record SalesSummary(
			long totalSoldItems,
			long totalCoinsPaid,
			long totalTransactions,
			List<Map<String, Object>> topItems,
			List<Map<String, Object>> topSellers
	) {}

	private final Path file;
	private final LongSupplier now;
	private final Object fileLock = new Object();
	private final List<SaleRecord> inMemory = new CopyOnWriteArrayList<>();

	public SalesTracker(Path dir, LongSupplier now) {
		this.file = dir.resolve("sales.jsonl");
		this.now = now;
		loadExisting();
	}

	private void loadExisting() {
		if (!Files.exists(file)) return;
		try {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			int start = Math.max(0, lines.size() - MAX_IN_MEMORY);
			for (int i = start; i < lines.size(); i++) {
				String line = lines.get(i).trim();
				if (line.isEmpty()) continue;
				try {
					JsonObject o = JsonParser.parseString(line).getAsJsonObject();
					SaleRecord r = parseRecord(o);
					if (r != null) inMemory.add(r);
				} catch (Exception ignored) {}
			}
		} catch (IOException e) {
			ClaimMap.LOGGER.warn("[timesmp] Could not read sales.jsonl: {}", e.getMessage());
		}
	}

	private SaleRecord parseRecord(JsonObject o) {
		if (o == null) return null;
		String id = o.has("id") ? o.get("id").getAsString() : "";
		long t = o.has("t") ? o.get("t").getAsLong() : 0L;
		String player = o.has("player") ? o.get("player").getAsString() : "unknown";
		String uuid = o.has("uuid") ? o.get("uuid").getAsString() : "";
		String itemId = o.has("itemId") ? o.get("itemId").getAsString() : "";
		String label = o.has("label") ? o.get("label").getAsString() : itemId;
		int count = o.has("count") ? o.get("count").getAsInt() : 0;
		long earned = o.has("earned") ? o.get("earned").getAsLong() : 0L;
		long unitPrice = o.has("unitPrice") ? o.get("unitPrice").getAsLong() : (count > 0 ? earned / count : 0L);
		String source = o.has("source") ? o.get("source").getAsString() : "shop";
		return new SaleRecord(id, t, player, uuid, itemId, label, count, earned, unitPrice, source);
	}

	public SaleRecord record(UUID playerUuid, String playerName, String itemId, String label,
	                         int count, long earned, long unitPrice, String source) {
		long t = now.getAsLong();
		String id = "sale_" + t + "_" + (playerUuid != null ? playerUuid.toString().substring(0, 8) : "x") + "_" + inMemory.size();
		SaleRecord record = new SaleRecord(
				id,
				t,
				playerName == null || playerName.isBlank() ? "Unknown" : playerName,
				playerUuid != null ? playerUuid.toString() : "",
				PriceService.normalize(itemId),
				label == null || label.isBlank() ? itemId : label,
				Math.max(1, count),
				Math.max(0, earned),
				Math.max(0, unitPrice),
				source == null ? "shop" : source
		);

		inMemory.add(record);
		if (inMemory.size() > MAX_IN_MEMORY * 2) {
			while (inMemory.size() > MAX_IN_MEMORY) {
				inMemory.remove(0);
			}
		}

		JsonObject o = new JsonObject();
		o.addProperty("id", record.id());
		o.addProperty("t", record.t());
		o.addProperty("player", record.player());
		o.addProperty("uuid", record.uuid());
		o.addProperty("itemId", record.itemId());
		o.addProperty("label", record.label());
		o.addProperty("count", record.count());
		o.addProperty("earned", record.earned());
		o.addProperty("unitPrice", record.unitPrice());
		o.addProperty("source", record.source());

		String line = GSON.toJson(o) + "\n";
		synchronized (fileLock) {
			try {
				Path parent = file.getParent();
				if (parent != null) Files.createDirectories(parent);
				Files.writeString(file, line, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
			} catch (IOException e) {
				ClaimMap.LOGGER.error("[timesmp] Could not append to sales.jsonl", e);
			}
		}
		return record;
	}

	public List<SaleRecord> tail(int limit, String filterPlayer, String filterItem) {
		String fPlayer = filterPlayer == null ? "" : filterPlayer.trim().toLowerCase(Locale.ROOT);
		String fItem = filterItem == null ? "" : filterItem.trim().toLowerCase(Locale.ROOT);

		List<SaleRecord> matches = new ArrayList<>();
		for (int i = inMemory.size() - 1; i >= 0; i--) {
			SaleRecord r = inMemory.get(i);
			if (!fPlayer.isEmpty() && !r.player().toLowerCase(Locale.ROOT).contains(fPlayer)) continue;
			if (!fItem.isEmpty() && !r.itemId().toLowerCase(Locale.ROOT).contains(fItem) && !r.label().toLowerCase(Locale.ROOT).contains(fItem)) continue;
			matches.add(r);
			if (matches.size() >= limit) break;
		}
		return matches;
	}

	public SalesSummary summary() {
		long totalSoldItems = 0;
		long totalCoinsPaid = 0;
		long totalTransactions = inMemory.size();

		Map<String, Long> itemCounts = new HashMap<>();
		Map<String, Long> itemEarned = new HashMap<>();
		Map<String, String> itemLabels = new HashMap<>();

		Map<String, Long> playerCounts = new HashMap<>();
		Map<String, Long> playerEarned = new HashMap<>();

		for (SaleRecord r : inMemory) {
			totalSoldItems += r.count();
			totalCoinsPaid += r.earned();

			itemCounts.merge(r.itemId(), (long) r.count(), Long::sum);
			itemEarned.merge(r.itemId(), r.earned(), Long::sum);
			itemLabels.putIfAbsent(r.itemId(), r.label());

			playerCounts.merge(r.player(), (long) r.count(), Long::sum);
			playerEarned.merge(r.player(), r.earned(), Long::sum);
		}

		List<Map<String, Object>> topItems = new ArrayList<>();
		itemEarned.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.limit(10)
				.forEach(e -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("itemId", e.getKey());
					m.put("label", itemLabels.getOrDefault(e.getKey(), e.getKey()));
					m.put("earned", e.getValue());
					m.put("count", itemCounts.getOrDefault(e.getKey(), 0L));
					topItems.add(m);
				});

		List<Map<String, Object>> topSellers = new ArrayList<>();
		playerEarned.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.limit(10)
				.forEach(e -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("player", e.getKey());
					m.put("earned", e.getValue());
					m.put("count", playerCounts.getOrDefault(e.getKey(), 0L));
					topSellers.add(m);
				});

		return new SalesSummary(totalSoldItems, totalCoinsPaid, totalTransactions, topItems, topSellers);
	}
}

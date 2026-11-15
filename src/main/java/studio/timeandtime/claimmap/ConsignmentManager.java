package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Items players hand to the server. Only the server may open auctions, so a
 * player deposits an item here and an admin later turns it into an auction.
 * Items stay in this file (not in any player's inventory) until auctioned.
 */
public final class ConsignmentManager {
	/** Returned server-auction items are listed under this pseudo player. */
	public static final String SERVER = "server";

	public static final class Entry {
		public String id;
		public String from;
		public String fromName;
		public String itemJson;
		public String itemId;
		public int count;
		public String label;
		public long depositedAt;
		public String status = "PENDING";
		public String auctionId;
	}

	public record Plan(String from, String fromName, String itemJson, String itemId, int count, String label) {
	}

	private static final Gson GSON = new GsonBuilder().create();

	private final Path file;
	private final LongSupplier now;
	private final List<Entry> entries = new ArrayList<>();
	private volatile boolean disabled;
	private int idCounter;

	public ConsignmentManager(Path dir, LongSupplier now) {
		this.file = dir.resolve("consignments.json");
		this.now = now;
	}

	public void load() {
		synchronized (Market.LOCK) {
			entries.clear();
			disabled = false;
			if (!Files.exists(file)) return;
			try {
				JsonObject root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
				if (root == null) throw new IllegalStateException("empty consignments.json");
				JsonArray arr = root.getAsJsonArray("entries");
				if (arr != null) {
					for (JsonElement el : arr) {
						Entry e = GSON.fromJson(el, Entry.class);
						if (e == null || e.id == null || e.from == null || e.itemJson == null || e.count < 1) {
							throw new IllegalStateException("invalid consignment row");
						}
						entries.add(e);
					}
				}
			} catch (Exception e) {
				disabled = true;
				entries.clear();
				ClaimMap.LOGGER.error("[timesmp] CONSIGNMENTS DISABLED — consignments.json: {}", e.getMessage());
				try {
					Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt-" + now.getAsLong()),
							StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException ignored) {
				}
			}
		}
	}

	private void save() {
		try {
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			JsonArray arr = new JsonArray();
			for (Entry e : entries) arr.add(GSON.toJsonTree(e));
			root.add("entries", arr);
			Path parent = file.getParent();
			if (parent != null) Files.createDirectories(parent);
			Path tmp = file.resolveSibling("consignments.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[timesmp] Could not save consignments.json", e);
		}
	}

	public boolean isDisabled() {
		return disabled;
	}

	public Entry add(Plan plan) {
		if (plan == null || plan.itemJson() == null || plan.itemJson().isBlank() || plan.count() < 1) return null;
		synchronized (Market.LOCK) {
			if (disabled) return null;
			Entry e = new Entry();
			e.id = newId();
			e.from = plan.from();
			e.fromName = plan.fromName();
			e.itemJson = plan.itemJson();
			e.itemId = plan.itemId();
			e.count = plan.count();
			e.label = plan.label();
			e.depositedAt = now.getAsLong();
			e.status = "PENDING";
			entries.add(e);
			save();
			return e;
		}
	}

	/** Stores an item returned from a cancelled server auction, once per auction. */
	public boolean putReturned(String itemJson, String itemId, int count, String label, String source) {
		synchronized (Market.LOCK) {
			if (source != null) {
				for (Entry e : entries) {
					if (source.equals(e.auctionId)) return true;
				}
			}
			return addReturned(itemJson, itemId, count, label, source) != null;
		}
	}

	private Entry addReturned(String itemJson, String itemId, int count, String label, String source) {
		if (disabled || itemJson == null || itemJson.isBlank() || count < 1) return null;
		Entry e = new Entry();
		e.id = newId();
		e.from = SERVER;
		e.fromName = "เซิร์ฟเวอร์";
		e.itemJson = itemJson;
		e.itemId = itemId;
		e.count = count;
		e.label = label;
		e.depositedAt = now.getAsLong();
		e.status = "PENDING";
		e.auctionId = source;
		entries.add(e);
		save();
		return e;
	}

	private String newId() {
		idCounter++;
		return "cs_" + Long.toString(now.getAsLong(), 36) + "_" + Integer.toString(idCounter, 36);
	}

	public List<Entry> pending() {
		List<Entry> out = new ArrayList<>();
		synchronized (Market.LOCK) {
			for (Entry e : entries) {
				if ("PENDING".equals(e.status)) out.add(e);
			}
		}
		out.sort(Comparator.comparingLong((Entry e) -> e.depositedAt).reversed());
		return out;
	}

	public List<Entry> pendingFrom(String uuid) {
		List<Entry> out = new ArrayList<>();
		synchronized (Market.LOCK) {
			for (Entry e : entries) {
				if ("PENDING".equals(e.status) && e.from.equals(uuid)) out.add(e);
			}
		}
		out.sort(Comparator.comparingLong((Entry e) -> e.depositedAt).reversed());
		return out;
	}

	public Entry find(String id) {
		synchronized (Market.LOCK) {
			for (Entry e : entries) if (e.id.equals(id)) return e;
			return null;
		}
	}

	/** Removes a pending entry so it can be turned into an auction. */
	public Entry take(String id) {
		synchronized (Market.LOCK) {
			for (int i = 0; i < entries.size(); i++) {
				Entry e = entries.get(i);
				if (!e.id.equals(id) || !"PENDING".equals(e.status)) continue;
				entries.remove(i);
				save();
				return e;
			}
			return null;
		}
	}

	/** Puts a taken entry back when auction creation fails. */
	public void restore(Entry e) {
		if (e == null) return;
		synchronized (Market.LOCK) {
			entries.add(e);
			save();
		}
	}
}

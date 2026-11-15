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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Item mailbox: bought items and returned auction items wait here when the
 * player is offline or their inventory is full (PHASES.md 5.2).
 *
 * <p>Stores {@code itemJson} as an opaque string, so this class has no
 * Minecraft dependency and is unit-testable.
 */
public final class MailboxManager {
	/** One mailbox slot, never more than one item stack. */
	public record MailEntry(String id, String itemJson, String itemId, int count, String label, String from, long t) {
	}

	private static final Gson GSON = new GsonBuilder().create();

	private final Path file;
	private final LongSupplier now;
	private final int maxEntries;
	private final Map<UUID, List<MailEntry>> boxes = new HashMap<>();
	private boolean disabled;

	public MailboxManager(Path file, LongSupplier now, int maxEntries) {
		this.file = file;
		this.now = now;
		this.maxEntries = Math.max(1, maxEntries);
	}

	// ---- persistence --------------------------------------------------------

	public void load() {
		synchronized (Market.LOCK) {
			boxes.clear();
			disabled = false;
			if (!Files.exists(file)) return;
			try {
				JsonObject root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
				if (root == null) throw new IllegalStateException("empty mailbox.json");
				JsonObject boxesJson = root.getAsJsonObject("boxes");
				if (boxesJson != null) {
					for (Map.Entry<String, JsonElement> e : boxesJson.entrySet()) {
						UUID uuid = UUID.fromString(e.getKey());
						if (!e.getValue().isJsonArray()) throw new IllegalStateException("boxes entry is not an array");
						List<MailEntry> list = new ArrayList<>();
						for (JsonElement el : e.getValue().getAsJsonArray()) {
							JsonObject o = el.getAsJsonObject();
							list.add(new MailEntry(
									str(o, "id"), str(o, "itemJson"), str(o, "itemId"),
									o.has("count") ? o.get("count").getAsInt() : 1,
									str(o, "label"), str(o, "from"),
									o.has("t") ? o.get("t").getAsLong() : 0L));
						}
						boxes.put(uuid, list);
					}
				}
			} catch (Exception e) {
				corrupt("cannot read mailbox.json: " + e.getMessage());
			}
		}
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	private void corrupt(String why) {
		disabled = true;
		boxes.clear();
		ClaimMap.LOGGER.error("[timesmp] MARKET DISABLED — mailbox.json: {}", why);
		try {
			Path target = file.resolveSibling(file.getFileName() + ".corrupt-" + now.getAsLong());
			Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
			ClaimMap.LOGGER.error("[timesmp] Moved damaged file to {}", target);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[timesmp] Could not move damaged mailbox.json aside", e);
		}
	}

	private void save() {
		try {
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			JsonObject boxesJson = new JsonObject();
			for (Map.Entry<UUID, List<MailEntry>> e : boxes.entrySet()) {
				JsonArray arr = new JsonArray();
				for (MailEntry m : e.getValue()) {
					JsonObject o = new JsonObject();
					o.addProperty("id", m.id());
					o.addProperty("itemJson", m.itemJson());
					o.addProperty("itemId", m.itemId());
					o.addProperty("count", m.count());
					o.addProperty("label", m.label());
					o.addProperty("from", m.from());
					o.addProperty("t", m.t());
					arr.add(o);
				}
				boxesJson.add(e.getKey().toString(), arr);
			}
			root.add("boxes", boxesJson);
			Path parent = file.getParent();
			if (parent != null) Files.createDirectories(parent);
			Path tmp = file.resolveSibling("mailbox.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[timesmp] Could not save mailbox.json", e);
		}
	}

	// ---- api ----------------------------------------------------------------

	public boolean isDisabled() {
		return disabled;
	}

	/** Adds an entry. A duplicate id is a no-op returning false (idempotent retries). */
	public boolean add(UUID to, MailEntry entry) {
		if (to == null || entry == null || entry.id() == null) return false;
		synchronized (Market.LOCK) {
			if (disabled) return false;
			List<MailEntry> list = boxes.computeIfAbsent(to, k -> new ArrayList<>());
			for (MailEntry existing : list) {
				if (existing.id().equals(entry.id())) return false;
			}
			if (list.size() >= maxEntries) return false;
			list.add(entry);
			save();
			return true;
		}
	}

	public boolean exists(UUID to, String id) {
		synchronized (Market.LOCK) {
			for (MailEntry m : boxes.getOrDefault(to, List.of())) {
				if (m.id().equals(id)) return true;
			}
			return false;
		}
	}

	public boolean hasCapacity(UUID to, int more) {
		if (more <= 0) return true;
		synchronized (Market.LOCK) {
			return boxes.getOrDefault(to, List.of()).size() + more <= maxEntries;
		}
	}

	public List<MailEntry> list(UUID to) {
		synchronized (Market.LOCK) {
			return List.copyOf(boxes.getOrDefault(to, List.of()));
		}
	}

	public int count(UUID to) {
		synchronized (Market.LOCK) {
			return boxes.getOrDefault(to, List.of()).size();
		}
	}

	/** Replaces one entry, or removes it when {@code updatedOrNull} is null. */
	public void replace(UUID to, String id, MailEntry updatedOrNull) {
		if (to == null || id == null) return;
		synchronized (Market.LOCK) {
			List<MailEntry> list = boxes.get(to);
			if (list == null) return;
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i).id().equals(id)) {
					if (updatedOrNull == null) list.remove(i);
					else list.set(i, updatedOrNull);
					save();
					return;
				}
			}
		}
	}

	/** Removes an entry without persisting per item (used when a claim consumed it). */
	public void remove(UUID to, String id) {
		replace(to, id, null);
	}

	public Map<String, Integer> counts() {
		Map<String, Integer> out = new LinkedHashMap<>();
		synchronized (Market.LOCK) {
			for (Map.Entry<UUID, List<MailEntry>> e : boxes.entrySet()) {
				out.put(e.getKey().toString().toLowerCase(Locale.ROOT), e.getValue().size());
			}
		}
		return out;
	}
}

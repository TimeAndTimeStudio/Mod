package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player item cache stored as {@code player-items/<uuid>.json}.
 *
 * <p>Every row keeps the opaque source ref <em>and</em> the concrete location
 * (inventory/ender/container, slot, dimension and block coordinates) so a
 * later web sale or market listing can decrement the exact stack it showed.
 * The cache is only a projection: the live inventory or saved player data is
 * still authoritative and is re-resolved by {@link ItemSources}.
 */
public final class PlayerItemStore {
	private static final Gson GSON = new GsonBuilder().create();
	private static final int MAX_ENTRIES = 2000;

	public record Snapshot(String uuid, String name, String source, long updatedAt,
			List<ItemSources.Entry> items) {
		public long totalCount() {
			long total = 0;
			for (ItemSources.Entry e : items) total += e.count();
			return total;
		}
	}

	private final Path dir;
	private final Object lock = new Object();
	private final ConcurrentHashMap<UUID, Snapshot> memory = new ConcurrentHashMap<>();

	public PlayerItemStore(Path dir) {
		this.dir = dir;
	}

	public Path fileFor(UUID uuid) {
		return dir.resolve(uuid + ".json");
	}

	public Optional<Snapshot> get(UUID uuid) {
		if (uuid == null) return Optional.empty();
		Snapshot cached = memory.get(uuid);
		if (cached != null) return Optional.of(cached);
		synchronized (lock) {
			cached = memory.get(uuid);
			if (cached != null) return Optional.of(cached);
			Snapshot loaded = read(uuid);
			if (loaded != null) memory.put(uuid, loaded);
			return Optional.ofNullable(loaded);
		}
	}

	/** Replaces the cache with a full scan. Identical content is not rewritten. */
	public Snapshot record(UUID uuid, String name, String source, List<ItemSources.Entry> items) {
		if (uuid == null) throw new IllegalArgumentException("uuid");
		List<ItemSources.Entry> clean = clean(items);
		synchronized (lock) {
			Snapshot old = memory.get(uuid);
			if (old == null) old = read(uuid);
			if (old != null && Objects.equals(old.name(), name) && old.items().equals(clean)) {
				memory.put(uuid, old);
				return old;
			}
			Snapshot snap = new Snapshot(uuid.toString(), name == null ? "" : name,
					source == null ? "unknown" : source, System.currentTimeMillis(), clean);
			memory.put(uuid, snap);
			write(snap);
			return snap;
		}
	}

	/**
	 * Replaces only inventory/ender rows and keeps known container rows. Used by
	 * the 5-second live tick: cheap, and it catches items used or picked up.
	 */
	public Snapshot mergeCarried(UUID uuid, String name, List<ItemSources.Entry> carried) {
		if (uuid == null) throw new IllegalArgumentException("uuid");
		Snapshot old = get(uuid).orElse(null);
		List<ItemSources.Entry> merged = new ArrayList<>();
		if (old != null) {
			for (ItemSources.Entry e : old.items()) {
				if (!isCarried(e)) merged.add(e);
			}
		}
		for (ItemSources.Entry e : clean(carried)) {
			if (isCarried(e)) merged.add(e);
		}
		return record(uuid, name, "live", merged);
	}

	private static boolean isCarried(ItemSources.Entry e) {
		return e.ref() != null && (e.ref().startsWith("inv|") || e.ref().startsWith("ender|"));
	}

	private static List<ItemSources.Entry> clean(List<ItemSources.Entry> items) {
		if (items == null || items.isEmpty()) return List.of();
		List<ItemSources.Entry> out = new ArrayList<>();
		for (ItemSources.Entry e : items) {
			if (e == null || e.ref() == null || e.itemId() == null || e.count() < 1) continue;
			out.add(e);
			if (out.size() >= MAX_ENTRIES) break;
		}
		return List.copyOf(out);
	}

	private Snapshot read(UUID uuid) {
		Path file = fileFor(uuid);
		if (!Files.exists(file)) return null;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root == null) return null;
			String name = str(root, "name");
			String source = str(root, "source");
			long updatedAt = root.has("updatedAt") ? root.get("updatedAt").getAsLong() : 0L;
			List<ItemSources.Entry> items = new ArrayList<>();
			JsonArray arr = root.getAsJsonArray("items");
			if (arr != null) {
				for (JsonElement el : arr) {
					if (!el.isJsonObject()) continue;
					JsonObject o = el.getAsJsonObject();
					String ref = str(o, "ref");
					String itemId = str(o, "itemId");
					if (ref == null || itemId == null) continue;
					items.add(new ItemSources.Entry(
							ref,
							str(o, "area") == null ? "" : str(o, "area"),
							itemId,
							str(o, "label") == null ? itemId : str(o, "label"),
							Math.max(1, intOf(o, "count", 1)),
							str(o, "container") == null ? "unknown" : str(o, "container"),
							intOf(o, "slot", -1),
							str(o, "dimension"),
							intOrNull(o, "x"), intOrNull(o, "y"), intOrNull(o, "z")));
					if (items.size() >= MAX_ENTRIES) break;
				}
			}
			return new Snapshot(uuid.toString(), name == null ? "" : name,
					source == null ? "unknown" : source, updatedAt, List.copyOf(items));
		} catch (Exception e) {
			// A cache is rebuildable; never let a damaged file disable the market.
			ClaimMap.LOGGER.warn("[claimmap] Could not read player-items/{}.json: {}", uuid, e.getMessage());
			return null;
		}
	}

	private void write(Snapshot snap) {
		try {
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			root.addProperty("uuid", snap.uuid());
			root.addProperty("name", snap.name());
			root.addProperty("source", snap.source());
			root.addProperty("updatedAt", snap.updatedAt());
			root.addProperty("stacks", snap.items().size());
			root.addProperty("totalCount", snap.totalCount());
			JsonArray arr = new JsonArray();
			for (ItemSources.Entry e : snap.items()) {
				JsonObject o = new JsonObject();
				o.addProperty("ref", e.ref());
				o.addProperty("area", e.area());
				o.addProperty("container", e.container());
				o.addProperty("slot", e.slot());
				o.addProperty("dimension", e.dimension());
				if (e.x() != null) o.addProperty("x", e.x());
				if (e.y() != null) o.addProperty("y", e.y());
				if (e.z() != null) o.addProperty("z", e.z());
				o.addProperty("itemId", e.itemId());
				o.addProperty("label", e.label());
				o.addProperty("count", e.count());
				arr.add(o);
			}
			root.add("items", arr);

			Files.createDirectories(dir);
			Path file = fileFor(UUID.fromString(snap.uuid()));
			Path tmp = Files.createTempFile(dir, "player-items-", ".tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFailed) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException e) {
			ClaimMap.LOGGER.error("[claimmap] Could not write player-items/{}.json", snap.uuid(), e);
		}
	}

	private static String str(JsonObject o, String key) {
		if (!o.has(key) || o.get(key).isJsonNull()) return null;
		try {
			return o.get(key).getAsString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static int intOf(JsonObject o, String key, int def) {
		Integer v = intOrNull(o, key);
		return v == null ? def : v;
	}

	private static Integer intOrNull(JsonObject o, String key) {
		if (!o.has(key) || o.get(key).isJsonNull()) return null;
		try {
			return o.get(key).getAsInt();
		} catch (RuntimeException e) {
			return null;
		}
	}
}

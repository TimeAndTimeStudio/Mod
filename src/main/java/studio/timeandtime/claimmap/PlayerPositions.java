package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the last known world coordinates of players so the web map can pan
 * to their location when opened.
 */
public final class PlayerPositions {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<UUID, BlockPos> POSITIONS = new ConcurrentHashMap<>();
	private static Path storageFile;

	public static void init(Path dataDir) {
		storageFile = dataDir.resolve("positions.json");
		load();
	}

	public static void update(ServerPlayer player) {
		if (player == null) return;
		POSITIONS.put(player.getUUID(), player.blockPosition());
	}

	public static BlockPos get(UUID uuid) {
		if (uuid == null) return null;
		return POSITIONS.get(uuid);
	}

	public static void load() {
		if (storageFile == null || !Files.exists(storageFile)) return;
		try {
			String json = Files.readString(storageFile, StandardCharsets.UTF_8);
			Type type = new TypeToken<Map<String, int[]>>() {}.getType();
			Map<String, int[]> map = GSON.fromJson(json, type);
			if (map != null) {
				for (Map.Entry<String, int[]> e : map.entrySet()) {
					try {
						UUID id = UUID.fromString(e.getKey());
						int[] pos = e.getValue();
						if (pos != null && pos.length >= 3) {
							POSITIONS.put(id, new BlockPos(pos[0], pos[1], pos[2]));
						}
					} catch (RuntimeException ignored) {
					}
				}
			}
		} catch (Exception e) {
			ClaimMap.LOGGER.warn("[Time SMP] Could not load positions.json: {}", e.getMessage());
		}
	}

	public static void save() {
		if (storageFile == null) return;
		try {
			Map<String, int[]> map = new HashMap<>();
			for (Map.Entry<UUID, BlockPos> e : POSITIONS.entrySet()) {
				BlockPos p = e.getValue();
				map.put(e.getKey().toString(), new int[]{p.getX(), p.getY(), p.getZ()});
			}
			Path parent = storageFile.getParent();
			if (parent != null) Files.createDirectories(parent);
			Path tmp = storageFile.resolveSibling("positions.json.tmp");
			Files.writeString(tmp, GSON.toJson(map), StandardCharsets.UTF_8);
			Files.move(tmp, storageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[Time SMP] Could not save positions.json", e);
		}
	}
}

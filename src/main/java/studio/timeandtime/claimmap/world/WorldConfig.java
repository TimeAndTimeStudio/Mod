package studio.timeandtime.claimmap.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Per-world configuration loaded from {@code world.json} or {@code <worldName>.json}
 * in the world's folder.
 */
public record WorldConfig(
		GameType gameMode,
		boolean randomSpawn,
		int randomSpawnRadius
) {
	private static final Logger LOGGER = LoggerFactory.getLogger("timesmp-worlds");

	public static final int DEFAULT_RANDOM_SPAWN_RADIUS = 2500;

	public static WorldConfig load(Path worldFolder, String worldName) {
		// Defaults:
		// lobby -> Adventure, randomSpawn = false
		// resource -> Survival, randomSpawn = true, radius = 2500
		// other -> Survival, randomSpawn = false
		GameType defaultMode = "lobby".equalsIgnoreCase(worldName) ? GameType.ADVENTURE : GameType.SURVIVAL;
		boolean defaultRandomSpawn = "resource".equalsIgnoreCase(worldName);
		int defaultRadius = DEFAULT_RANDOM_SPAWN_RADIUS;

		Path configFile = findConfigFile(worldFolder, worldName);
		if (configFile == null || !Files.exists(configFile)) {
			return new WorldConfig(defaultMode, defaultRandomSpawn, defaultRadius);
		}

		try {
			String jsonStr = Files.readString(configFile, StandardCharsets.UTF_8);
			JsonObject obj = JsonParser.parseString(jsonStr).getAsJsonObject();

			GameType mode = defaultMode;
			if (obj.has("gamemode") || obj.has("gameMode")) {
				JsonElement el = obj.has("gamemode") ? obj.get("gamemode") : obj.get("gameMode");
				mode = parseGameMode(el.getAsString(), defaultMode);
			}

			boolean randomSpawn = defaultRandomSpawn;
			if (obj.has("random_spawn") || obj.has("randomSpawn")) {
				JsonElement el = obj.has("random_spawn") ? obj.get("random_spawn") : obj.get("randomSpawn");
				randomSpawn = el.getAsBoolean();
			} else if (obj.has("random_spawn_radius") || obj.has("randomSpawnRadius")) {
				// If radius is specified and > 0, assume random spawn is enabled
				randomSpawn = true;
			}

			int radius = defaultRadius;
			if (obj.has("random_spawn_radius") || obj.has("randomSpawnRadius") || obj.has("radius")) {
				JsonElement el = obj.has("random_spawn_radius") ? obj.get("random_spawn_radius")
						: (obj.has("randomSpawnRadius") ? obj.get("randomSpawnRadius") : obj.get("radius"));
				radius = Math.max(10, el.getAsInt());
			}

			LOGGER.info("[worlds] Loaded config for world '{}': gamemode={}, randomSpawn={}, radius={}",
					worldName, mode, randomSpawn, radius);
			return new WorldConfig(mode, randomSpawn, radius);
		} catch (Exception e) {
			LOGGER.warn("[worlds] Failed to read config in {}: {}, using defaults", configFile, e.getMessage());
			return new WorldConfig(defaultMode, defaultRandomSpawn, defaultRadius);
		}
	}

	private static Path findConfigFile(Path worldFolder, String worldName) {
		Path p1 = worldFolder.resolve("world.json");
		if (Files.exists(p1)) return p1;
		Path p2 = worldFolder.resolve(worldName + ".json");
		if (Files.exists(p2)) return p2;
		Path p3 = worldFolder.resolve("config.json");
		if (Files.exists(p3)) return p3;
		return null;
	}

	public static GameType parseGameMode(String str, GameType fallback) {
		if (str == null) return fallback;
		String lower = str.trim().toLowerCase(Locale.ROOT);
		return switch (lower) {
			case "0", "survival", "s" -> GameType.SURVIVAL;
			case "1", "creative", "c" -> GameType.CREATIVE;
			case "2", "adventure", "a" -> GameType.ADVENTURE;
			case "3", "spectator", "sp" -> GameType.SPECTATOR;
			default -> fallback;
		};
	}
}

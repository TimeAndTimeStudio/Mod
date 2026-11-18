package studio.timeandtime.claimmap.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One world folder discovered under the server's {@code worlds/} directory.
 *
 * <p>The folder is a complete, independent Minecraft save. While it is loaded,
 * {@link #level} and {@link #storageAccess} are non-null and the world is part
 * of {@link net.minecraft.server.MinecraftServer#getAllLevels()}.
 */
public final class WorldDefinition {
	private final String name;
	private final String folderName;
	private final Path folder;
	private final ResourceKey<Level> dimensionKey;

	volatile WorldState state = WorldState.DISCOVERED;
	volatile String error;
	volatile boolean present = true;
	volatile boolean commandAvailable;
	volatile String commandConflict;
	volatile long lastEmptyTick;
	volatile ServerLevel level;
	volatile LevelStorageSource.LevelStorageAccess storageAccess;
	volatile WorldData worldData;
	volatile WorldConfig config;

	private static final Logger LOGGER = LoggerFactory.getLogger("timesmp-worlds");

	private final Map<UUID, SavedPos> savedPositions = new ConcurrentHashMap<>();
	private final Path positionsFile;

	WorldDefinition(String name, String folderName, Path folder, ResourceKey<Level> dimensionKey) {
		this.name = name;
		this.folderName = folderName;
		this.folder = folder;
		this.dimensionKey = dimensionKey;
		this.positionsFile = folder.resolve(".saved_positions.json");
		loadSavedPositions();
	}

	public SavedPos getSavedPosition(UUID uuid) {
		if (uuid == null) return null;
		return savedPositions.get(uuid);
	}

	public void recordPosition(UUID uuid, double x, double y, double z, float yaw, float pitch) {
		if (uuid == null) return;
		savedPositions.put(uuid, new SavedPos(x, y, z, yaw, pitch));
		saveSavedPositions();
	}

	public void clearSavedPosition(UUID uuid) {
		if (uuid != null && savedPositions.remove(uuid) != null) {
			saveSavedPositions();
		}
	}

	public void loadSavedPositions() {
		if (positionsFile == null || !Files.exists(positionsFile)) return;
		try {
			String json = Files.readString(positionsFile, StandardCharsets.UTF_8);
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : root.entrySet()) {
				if (!e.getValue().isJsonObject()) continue;
				JsonObject o = e.getValue().getAsJsonObject();
				UUID u = UUID.fromString(e.getKey());
				savedPositions.put(u, new SavedPos(
						o.get("x").getAsDouble(),
						o.get("y").getAsDouble(),
						o.get("z").getAsDouble(),
						o.has("yaw") ? o.get("yaw").getAsFloat() : 0f,
						o.has("pitch") ? o.get("pitch").getAsFloat() : 0f
				));
			}
		} catch (Exception e) {
			LOGGER.warn("[worlds] Could not read saved positions for {}: {}", name, e.getMessage());
		}
	}

	public void saveSavedPositions() {
		if (positionsFile == null) return;
		try {
			JsonObject root = new JsonObject();
			for (Map.Entry<UUID, SavedPos> e : savedPositions.entrySet()) {
				JsonObject o = new JsonObject();
				o.addProperty("x", e.getValue().x());
				o.addProperty("y", e.getValue().y());
				o.addProperty("z", e.getValue().z());
				o.addProperty("yaw", e.getValue().yaw());
				o.addProperty("pitch", e.getValue().pitch());
				root.add(e.getKey().toString(), o);
			}
			Files.writeString(positionsFile, root.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			LOGGER.warn("[worlds] Could not save positions for {}: {}", name, e.getMessage());
		}
	}

	public String name() {
		return name;
	}

	public String folderName() {
		return folderName;
	}

	public Path folder() {
		return folder;
	}

	public ResourceKey<Level> dimensionKey() {
		return dimensionKey;
	}

	public WorldState state() {
		return state;
	}

	public String error() {
		return error;
	}

	public boolean present() {
		return present;
	}

	public boolean commandAvailable() {
		return commandAvailable;
	}

	public String commandConflict() {
		return commandConflict;
	}

	public ServerLevel level() {
		return level;
	}

	public WorldConfig config() {
		return config;
	}

	public void reloadConfig() {
		this.config = WorldConfig.load(folder, name);
	}

	public boolean loaded() {
		return state == WorldState.LOADED && level != null;
	}
}

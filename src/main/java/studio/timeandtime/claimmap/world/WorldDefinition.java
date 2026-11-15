package studio.timeandtime.claimmap.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;

import java.nio.file.Path;

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

	WorldDefinition(String name, String folderName, Path folder, ResourceKey<Level> dimensionKey) {
		this.name = name;
		this.folderName = folderName;
		this.folder = folder;
		this.dimensionKey = dimensionKey;
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

	public boolean loaded() {
		return state == WorldState.LOADED && level != null;
	}
}

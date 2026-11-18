package studio.timeandtime.claimmap.world;

import net.minecraft.world.level.storage.LevelStorageSource;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Marks the {@link LevelStorageSource.LevelStorageAccess} instances that belong
 * to a managed world folder.
 *
 * <p>A managed save is a complete world at {@code worlds/<name>/}. Modern saves
 * keep their overworld chunks under {@code dimensions/minecraft/overworld};
 * older saves keep them in the folder root. Minecraft normally stores a
 * non-overworld dimension under {@code dimensions/<namespace>/<path>}; the
 * small mixin on {@code LevelStorageAccess.getDimensionPath} routes managed
 * saves to their own overworld data so the existing region files are used
 * instead of copied.
 */
public final class WorldStoragePaths {
	private static final Set<LevelStorageSource.LevelStorageAccess> MANAGED =
			Collections.newSetFromMap(new IdentityHashMap<>());

	private WorldStoragePaths() {
	}

	public static void markManaged(LevelStorageSource.LevelStorageAccess access) {
		MANAGED.add(access);
	}

	public static void unmarkManaged(LevelStorageSource.LevelStorageAccess access) {
		MANAGED.remove(access);
	}

	public static boolean isManaged(LevelStorageSource.LevelStorageAccess access) {
		return MANAGED.contains(access);
	}
}

package studio.timeandtime.claimmap.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import studio.timeandtime.claimmap.ClaimMap;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the End main dragon island (500x500 area, X: -250..250, Z: -250..250).
 * Natural island blocks cannot be broken, but players can place temporary blocks
 * and break blocks placed by players (or iron bars).
 * On server shutdown, all player placed blocks are automatically reverted to Air.
 */
public final class EndArenaManager {
	public static final int ARENA_MIN_X = -250;
	public static final int ARENA_MAX_X = 250;
	public static final int ARENA_MIN_Z = -250;
	public static final int ARENA_MAX_Z = 250;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type POS_LIST_TYPE = new TypeToken<List<long[]>>() {}.getType();

	private final Path storageFile;
	private final Set<BlockPos> placedBlocks = ConcurrentHashMap.newKeySet();
	private final Object saveLock = new Object();

	public EndArenaManager(Path dataDir) {
		this.storageFile = dataDir.resolve(".end_placed_blocks.json");
	}

	public static boolean isMainIsland(Level level, BlockPos pos) {
		if (level == null || level.dimension() != Level.END) return false;
		return isInsideArena(pos.getX(), pos.getZ());
	}

	public static boolean isInsideArena(int x, int z) {
		return x >= ARENA_MIN_X && x <= ARENA_MAX_X && z >= ARENA_MIN_Z && z <= ARENA_MAX_Z;
	}

	/**
	 * Checks if the given chunk overlaps with the 500x500 central End arena.
	 * 500x500 block coordinates (-250 to 250) map to chunk coordinates:
	 * floorDiv(-250, 16) = -16, floorDiv(250, 16) = 15.
	 */
	public static boolean isArenaChunk(int cx, int cz) {
		return cx >= -16 && cx <= 15 && cz >= -16 && cz <= 15;
	}

	public boolean isPlayerPlaced(BlockPos pos) {
		return placedBlocks.contains(pos.immutable());
	}

	public void recordPlaced(BlockPos pos) {
		placedBlocks.add(pos.immutable());
	}

	public void recordBroken(BlockPos pos) {
		placedBlocks.remove(pos.immutable());
	}

	public Set<BlockPos> getPlacedBlocks() {
		return Collections.unmodifiableSet(placedBlocks);
	}

	public void load() {
		placedBlocks.clear();
		if (storageFile == null || !Files.exists(storageFile)) return;
		try {
			String json = new String(Files.readAllBytes(storageFile), StandardCharsets.UTF_8);
			List<long[]> raw = GSON.fromJson(json, POS_LIST_TYPE);
			if (raw != null) {
				for (long[] coords : raw) {
					if (coords != null && coords.length >= 3) {
						placedBlocks.add(new BlockPos((int) coords[0], (int) coords[1], (int) coords[2]));
					}
				}
			}
			ClaimMap.LOGGER.info("[timesmp] Loaded {} placed blocks in End arena", placedBlocks.size());
		} catch (Exception e) {
			ClaimMap.LOGGER.error("[timesmp] Could not load .end_placed_blocks.json", e);
		}
	}

	public void save() {
		synchronized (saveLock) {
			try {
				Path parent = storageFile.getParent();
				if (parent != null) Files.createDirectories(parent);
				List<long[]> raw = new ArrayList<>(placedBlocks.size());
				for (BlockPos pos : placedBlocks) {
					raw.add(new long[]{pos.getX(), pos.getY(), pos.getZ()});
				}
				String json = GSON.toJson(raw);
				Path tmp = storageFile.resolveSibling(".end_placed_blocks.json.tmp");
				Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
				Files.move(tmp, storageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				ClaimMap.LOGGER.error("[timesmp] Could not save .end_placed_blocks.json", e);
			}
		}
	}

	/**
	 * Cleans up all player-placed blocks on server shutdown and deletes the tracking file.
	 */
	public void cleanupPlacedBlocks(ServerLevel endLevel) {
		if (endLevel == null) return;
		int count = placedBlocks.size();
		if (count == 0) {
			try {
				Files.deleteIfExists(storageFile);
			} catch (IOException ignored) {}
			return;
		}

		ClaimMap.LOGGER.info("[timesmp] Cleaning up {} player placed blocks in The End arena...", count);
		for (BlockPos pos : placedBlocks) {
			try {
				if (!endLevel.getBlockState(pos).isAir()) {
					endLevel.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
				}
			} catch (Exception e) {
				ClaimMap.LOGGER.warn("[timesmp] Failed to reset End block at {}: {}", pos, e.getMessage());
			}
		}
		placedBlocks.clear();
		try {
			Files.deleteIfExists(storageFile);
		} catch (IOException ignored) {}
		ClaimMap.LOGGER.info("[timesmp] End arena clean-up completed.");
	}
}

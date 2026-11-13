package studio.timeandtime.claimmap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Renders the map using only chunks that have actually been loaded / explored
 * (no pre-generation). One PNG per 500x500 cell, stored in
 * {@code config/claimmap/tiles/} (plan.md, MapRenderer).
 */
public final class MapRenderer {
	private final ClaimMapConfig config;
	private final Map<String, Tile> tiles = new ConcurrentHashMap<>();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "claimmap-map-flusher");
		t.setDaemon(true);
		return t;
	});

	static final class Tile {
		final BufferedImage img;
		final int size;
		volatile boolean explored;
		volatile boolean dirty;

		Tile(int size) {
			this.size = size;
			this.img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		}
	}

	public MapRenderer(ClaimMapConfig config) {
		this.config = config;
	}

	public void start() {
		scheduler.scheduleWithFixedDelay(this::flush, config.saveDelayMs, config.saveDelayMs, TimeUnit.MILLISECONDS);
	}

	public void stop() {
		scheduler.shutdown();
		try {
			scheduler.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException ignored) {
		}
	}

	/** Called from the server thread for every newly loaded chunk. */
	public void onChunkLoaded(ServerLevel level, LevelChunk chunk) {
		int chunkX = chunk.getPos().x();
		int chunkZ = chunk.getPos().z();
		int step = Math.max(1, config.renderScale);
		for (int dx = 0; dx < 16; dx += step) {
			for (int dz = 0; dz < 16; dz += step) {
				int x = chunkX * 16 + dx;
				int z = chunkZ * 16 + dz;
				// NOTE: do NOT call level.getHeight(WorldSurface,x,z) in a chunk event:
				// it block-fetches chunks and deadlocks the server thread while chunks load.
				// Read the height from the chunk's own heightmap instead (non-blocking;
				// the method masks to chunk-local coordinates and is safe on a loaded chunk).
				int topY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				BlockState state = chunk.getBlockState(new BlockPos(x, topY, z));
				draw(x, z, classify(state));
			}
		}
	}

	/** Returns the tile image for a cell, or null when it has no explored chunk yet. */
	public BufferedImage tileImage(int cx, int cz) {
		Tile t = tiles.get(cx + "," + cz);
		return t == null || !t.explored ? null : t.img;
	}

	/** Encodes the current tile PNG bytes, or returns null when unexplored / unloaded. */
	public byte[] encodedPng(int cx, int cz) {
		Tile t = tiles.get(cx + "," + cz);
		if (t == null || !t.explored) return null;
		synchronized (t) {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			try {
				ImageIO.write(t.img, "png", out);
				return out.toByteArray();
			} catch (IOException e) {
				return null;
			}
		}
	}

	public void flush() {
		Path dir = config.tilesDir();
		for (Map.Entry<String, Tile> e : tiles.entrySet()) {
			Tile t = e.getValue();
			if (!t.dirty) continue;
			synchronized (t) {
				t.dirty = false;
				try {
					Path out = dir.resolve(e.getKey().replace(",", "_") + ".png");
					ImageIO.write(t.img, "png", out.toFile());
				} catch (IOException io) {
					ClaimMap.LOGGER.error("[claimmap] Could not write tile {}", e.getKey(), io);
				}
			}
		}
	}

	private void draw(int x, int z, int color) {
		int step = Math.max(1, config.renderScale);
		int cx = Math.floorDiv(x, config.cellSize);
		int cz = Math.floorDiv(z, config.cellSize);
		String id = cx + "," + cz;
		Tile t = tiles.computeIfAbsent(id, k -> new Tile(config.cellSize / step));
		synchronized (t) {
			int px = (x - cx * config.cellSize) / step;
			int pz = (z - cz * config.cellSize) / step;
			if (px >= 0 && px < t.size && pz >= 0 && pz < t.size) {
				t.img.setRGB(px, pz, 0xFF000000 | color);
			}
			t.explored = true;
			t.dirty = true;
		}
	}

	private int classify(BlockState state) {
		if (state.getFluidState().is(FluidTags.WATER)) return 0x346EC8;   // water = blue
		if (state.is(Blocks.SAND)) return 0xE8DDA6;                        // sand
		if (state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.SNOW)
				|| state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)) return 0xF0FAFA; // snow / ice
		return 0x5AAA46;                                                // everything else = green
	}
}

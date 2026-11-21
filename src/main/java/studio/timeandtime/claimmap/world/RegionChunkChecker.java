package studio.timeandtime.claimmap.world;

import net.minecraft.world.level.ChunkPos;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fast direct checker for existing chunks in Minecraft .mca region files.
 * Reads the 4KB header sector without loading the chunk into memory or triggering worldgen.
 */
public final class RegionChunkChecker {
	private static final Map<String, Boolean> REGION_FILE_EXIST_CACHE = new ConcurrentHashMap<>();

	private RegionChunkChecker() {}

	/**
	 * Checks if a chunk at (cx, cz) has already been generated and saved to disk.
	 */
	public static boolean doesChunkExist(File dimensionDir, int cx, int cz) {
		int rx = cx >> 5;
		int rz = cz >> 5;
		File regionDir = new File(dimensionDir, "region");
		File regionFile = new File(regionDir, "r." + rx + "." + rz + ".mca");

		if (!regionFile.exists() || regionFile.length() < 4096) {
			return false;
		}

		int localX = cx & 31;
		int localZ = cz & 31;
		long headerOffset = 4L * ((localX & 31) + (localZ & 31) * 32);

		try (RandomAccessFile raf = new RandomAccessFile(regionFile, "r")) {
			raf.seek(headerOffset);
			int b0 = raf.read();
			int b1 = raf.read();
			int b2 = raf.read();
			int sectorCount = raf.read();
			if (b0 < 0 || b1 < 0 || b2 < 0 || sectorCount < 0) {
				return false;
			}
			int offset = (b0 << 16) | (b1 << 8) | b2;
			return offset != 0 && sectorCount != 0;
		} catch (Exception e) {
			return false;
		}
	}
}

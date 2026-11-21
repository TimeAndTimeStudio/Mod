package studio.timeandtime.claimmap.world;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PregenManager: Manages controlled chunk generation across worlds.
 * Runs strictly in Square (สี่เหลี่ยมตามรัศมีบล็อก) and Auto-Detects existing chunks on disk (Option B)
 * so existing chunks from prior 1k runs or player exploration are automatically skipped.
 */
public class PregenManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("timesmp-pregen");
	private static final PregenManager INSTANCE = new PregenManager();

	public static PregenManager instance() {
		return INSTANCE;
	}

	public enum TaskStatus {
		RUNNING,
		PAUSED,
		COMPLETED,
		CANCELLED
	}

	public static class PregenTask {
		public final String worldName;
		public final int centerX;
		public final int centerZ;
		public final int radiusChunks;
		public final int totalChunks;
		public final int skippedExisting;

		public int currentChunkIndex = 0;
		public TaskStatus status = TaskStatus.RUNNING;
		public long startTime = System.currentTimeMillis();
		public long lastSaveTime = System.currentTimeMillis();

		// Transient list of chunks to generate
		public transient List<ChunkPos> chunkList = new ArrayList<>();
		public transient Set<ChunkPos> inFlight = ConcurrentHashMap.newKeySet();
		public transient AtomicInteger completedCount = new AtomicInteger(0);

		public PregenTask(String worldName, int centerX, int centerZ, int radiusChunks, File dimensionDir) {
			this.worldName = worldName;
			this.centerX = centerX;
			this.centerZ = centerZ;
			this.radiusChunks = radiusChunks;

			int skipped = 0;
			// Build square coordinates only
			for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
				for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
					int cx = centerX + dx;
					int cz = centerZ + dz;

					// Option B: Auto-detect if chunk already exists on disk (.mca)
					if (dimensionDir != null && RegionChunkChecker.doesChunkExist(dimensionDir, cx, cz)) {
						skipped++;
						continue; // Skip chunk already generated!
					}
					chunkList.add(new ChunkPos(cx, cz));
				}
			}

			this.skippedExisting = skipped;

			// Sort spiral out from center
			chunkList.sort(Comparator.comparingInt(c -> {
				int dx = c.x() - centerX;
				int dz = c.z() - centerZ;
				return Math.max(Math.abs(dx), Math.abs(dz)); // Square rings
			}));
			this.totalChunks = chunkList.size();
		}
	}

	private final Map<String, PregenTask> activeTasks = new ConcurrentHashMap<>();
	private final int maxInFlight = 16;
	private final double maxMspt = 45.0; // Throttle if server is struggling

	public synchronized boolean startTask(String worldName, int centerX, int centerZ, int radiusBlocks, CommandSourceStack source) {
		int radiusChunks = Math.max(1, radiusBlocks / 16);
		if (activeTasks.containsKey(worldName) && activeTasks.get(worldName).status == TaskStatus.RUNNING) {
			if (source != null) {
				source.sendFailure(Component.literal("§cมีงาน Pregen โลก " + worldName + " กำลังทำงานอยู่แล้ว"));
			}
			return false;
		}

		MinecraftServer server = source != null ? source.getServer() : null;
		File dimDir = null;
		if (server != null) {
			ServerLevel lvl = findLevel(server, worldName);
			if (lvl != null) {
				dimDir = getDimensionDirectory(server, lvl);
			}
		}

		PregenTask task = new PregenTask(worldName, centerX >> 4, centerZ >> 4, radiusChunks, dimDir);
		activeTasks.put(worldName, task);
		saveTaskState(task);

		String msg = String.format("§aเริ่ม Pregen โลก §e%s §a(สี่เหลี่ยม รัศมี %d บล็อก | ต้องทำใหม่: §e%d ชังก์ §a| ข้ามของเดิม: §b%d ชังก์§a)",
				worldName, radiusBlocks, task.totalChunks, task.skippedExisting);
		LOGGER.info(msg);
		if (source != null) {
			source.sendSuccess(() -> Component.literal(msg), true);
		}
		return true;
	}

	public synchronized boolean pauseTask(String worldName, CommandSourceStack source) {
		PregenTask task = activeTasks.get(worldName);
		if (task != null && task.status == TaskStatus.RUNNING) {
			task.status = TaskStatus.PAUSED;
			saveTaskState(task);
			if (source != null) source.sendSuccess(() -> Component.literal("§eหยุดชั่วคราว Pregen โลก " + worldName), true);
			return true;
		}
		if (source != null) source.sendFailure(Component.literal("§cไม่มีงาน Pregen ที่กำลังทำงานในโลก " + worldName));
		return false;
	}

	public synchronized boolean resumeTask(String worldName, CommandSourceStack source) {
		PregenTask task = activeTasks.get(worldName);
		if (task != null && task.status == TaskStatus.PAUSED) {
			task.status = TaskStatus.RUNNING;
			if (source != null) source.sendSuccess(() -> Component.literal("§aทำต่อ Pregen โลก " + worldName), true);
			return true;
		}
		if (source != null) source.sendFailure(Component.literal("§cไม่มีงาน Pregen ที่หยุดพักอยู่ในโลก " + worldName));
		return false;
	}

	public synchronized boolean cancelTask(String worldName, CommandSourceStack source) {
		PregenTask task = activeTasks.remove(worldName);
		if (task != null) {
			task.status = TaskStatus.CANCELLED;
			deleteTaskState(worldName);
			if (source != null) source.sendSuccess(() -> Component.literal("§cยกเลิกงาน Pregen โลก " + worldName + " เรียบร้อย"), true);
			return true;
		}
		if (source != null) source.sendFailure(Component.literal("§cไม่มีงาน Pregen ในโลก " + worldName));
		return false;
	}

	public void showStatus(String worldName, CommandSourceStack source) {
		if (worldName != null) {
			PregenTask task = activeTasks.get(worldName);
			if (task == null) {
				source.sendFailure(Component.literal("§cไม่มีงาน Pregen ในโลก " + worldName));
				return;
			}
			printTaskStatus(task, source);
		} else {
			if (activeTasks.isEmpty()) {
				source.sendSuccess(() -> Component.literal("§7ไม่มีงาน Pregen ที่กำลังดำเนินการ"), false);
				return;
			}
			for (PregenTask t : activeTasks.values()) {
				printTaskStatus(t, source);
			}
		}
	}

	private void printTaskStatus(PregenTask task, CommandSourceStack source) {
		int done = task.completedCount.get();
		int total = task.totalChunks;
		double pct = total > 0 ? (done * 100.0 / total) : 100.0;
		long elapsedSec = (System.currentTimeMillis() - task.startTime) / 1000;
		double cps = elapsedSec > 0 ? (double) done / elapsedSec : 0.0;
		long leftSec = cps > 0 ? (long) ((total - done) / cps) : 0;

		String text = String.format("§6[%s] §fสถานะ: §e%s §7| §fเสร็จ: §a%d/%d (%.1f%%) §7| §fข้ามเดิม: §b%d §7| §fความเร็ว: §b%.1f ch/s §7| §fเหลือ: §e%02d:%02d",
				task.worldName, task.status, done, total, pct, task.skippedExisting, cps, leftSec / 60, leftSec % 60);
		source.sendSuccess(() -> Component.literal(text), false);
	}

	/**
	 * Called every server tick from ServerTickEvents.END_SERVER_TICK.
	 */
	public void tick(MinecraftServer server) {
		if (activeTasks.isEmpty()) return;

		// Check server load
		double currentMspt = server.getAverageTickTimeNanos() / 1_000_000.0;
		if (currentMspt > maxMspt) {
			// Throttle if server is experiencing lag spikes
			return;
		}

		for (Iterator<Map.Entry<String, PregenTask>> it = activeTasks.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, PregenTask> entry = it.next();
			PregenTask task = entry.getValue();

			if (task.status != TaskStatus.RUNNING) {
				if (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.CANCELLED) {
					it.remove();
				}
				continue;
			}

			// Find ServerLevel for task
			ServerLevel level = findLevel(server, task.worldName);
			if (level == null) {
				continue;
			}

			// Pump tasks up to maxInFlight
			while (task.inFlight.size() < maxInFlight && task.currentChunkIndex < task.chunkList.size()) {
				ChunkPos pos = task.chunkList.get(task.currentChunkIndex++);
				task.inFlight.add(pos);

				// Request chunk future asynchronously at FULL status
				level.getChunkSource().getChunkFuture(pos.x(), pos.z(), ChunkStatus.FULL, true).thenAccept(result -> {
					task.inFlight.remove(pos);
					if (result.isSuccess()) {
						task.completedCount.incrementAndGet();
					}
				});
			}

			// Save state periodically
			if (System.currentTimeMillis() - task.lastSaveTime > 10_000) {
				task.lastSaveTime = System.currentTimeMillis();
				saveTaskState(task);
			}

			// Check completion
			if (task.currentChunkIndex >= task.chunkList.size() && task.inFlight.isEmpty()) {
				task.status = TaskStatus.COMPLETED;
				deleteTaskState(task.worldName);
				LOGGER.info("§aPregen โลก {} เสร็จสมบูรณ์! รวมสร้างใหม่ {} ชังก์ (ข้ามชังก์เดิม {})",
						task.worldName, task.totalChunks, task.skippedExisting);
				it.remove();
			}
		}
	}

	public ServerLevel findLevel(MinecraftServer server, String worldName) {
		if ("main".equalsIgnoreCase(worldName) || "overworld".equalsIgnoreCase(worldName)) {
			return server.overworld();
		}
		for (ServerLevel lvl : server.getAllLevels()) {
			String path = lvl.dimension().identifier().getPath();
			if (path.equalsIgnoreCase(worldName)) {
				return lvl;
			}
		}
		return null;
	}

	public File getDimensionDirectory(MinecraftServer server, ServerLevel level) {
		File root = server.getWorldPath(LevelResource.ROOT).toFile();
		String path = level.dimension().identifier().getPath();
		if ("overworld".equals(path) || level == server.overworld()) {
			return root;
		} else if ("the_nether".equals(path)) {
			return new File(root, "DIM-1");
		} else if ("the_end".equals(path)) {
			return new File(root, "DIM1");
		} else {
			return new File(new File(root, "dimensions"), level.dimension().identifier().getNamespace() + "/" + path);
		}
	}

	private File getSaveFile(String worldName) {
		File dir = new File("config/timesmp/pregen");
		if (!dir.exists()) dir.mkdirs();
		return new File(dir, worldName + ".properties");
	}

	private void saveTaskState(PregenTask task) {
		try {
			Properties props = new Properties();
			props.setProperty("world", task.worldName);
			props.setProperty("centerX", String.valueOf(task.centerX));
			props.setProperty("centerZ", String.valueOf(task.centerZ));
			props.setProperty("radius", String.valueOf(task.radiusChunks));
			props.setProperty("currentIndex", String.valueOf(task.currentChunkIndex));
			props.setProperty("completed", String.valueOf(task.completedCount.get()));
			props.setProperty("status", task.status.name());

			try (FileWriter fw = new FileWriter(getSaveFile(task.worldName))) {
				props.store(fw, "TimeSMP Pregen State");
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to save pregen state for {}: {}", task.worldName, e.getMessage());
		}
	}

	private void deleteTaskState(String worldName) {
		try {
			File f = getSaveFile(worldName);
			if (f.exists()) f.delete();
		} catch (Exception ignored) {}
	}
}

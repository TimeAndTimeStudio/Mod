package studio.timeandtime.claimmap.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.timeandtime.claimmap.ServerSettings;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Handles automated world backups on server shutdown.
 * Backups are stored as ZIP archives in the configured directory.
 */
public final class WorldBackupManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("timesmp-backup");
	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private WorldBackupManager() {
	}

	public static void performBackup(MinecraftServer server, ServerSettings settings) {
		if (server == null || settings == null) return;
		if (!settings.backupEnabled) {
			LOGGER.info("[backup] World backup is disabled in setting.json");
			return;
		}

		Path worldRoot;
		try {
			worldRoot = server.getWorldPath(LevelResource.ROOT).toRealPath();
		} catch (Exception e) {
			worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		}

		if (!Files.exists(worldRoot)) {
			LOGGER.warn("[backup] World root directory does not exist: {}", worldRoot);
			return;
		}

		// Save all chunks and flush data before archiving
		try {
			LOGGER.info("[backup] Flushing world data to disk before backup...");
			server.saveAllChunks(true, true, true);
		} catch (Exception e) {
			LOGGER.warn("[backup] Error while saving chunks before backup: {}", e.getMessage());
		}

		Path backupDir;
		String configuredPath = settings.backupPath != null && !settings.backupPath.isBlank()
				? settings.backupPath.trim()
				: "backups";
		Path pathObj = Paths.get(configuredPath);
		if (pathObj.isAbsolute()) {
			backupDir = pathObj.normalize();
		} else {
			// Relative to server root (parent of worldRoot or working dir)
			Path serverRoot = Paths.get(".").toAbsolutePath().normalize();
			backupDir = serverRoot.resolve(pathObj).normalize();
		}

		createZipBackup(worldRoot, backupDir, settings.maxBackups);
	}

	/**
	 * Archives the source directory into targetDir as a ZIP file, then rotates old backups.
	 */
	public static Path createZipBackup(Path sourceDir, Path targetDir, int maxBackups) {
		try {
			Files.createDirectories(targetDir);
		} catch (IOException e) {
			LOGGER.error("[backup] Could not create backup directory {}: {}", targetDir, e.getMessage());
			return null;
		}

		String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
		String zipFileName = "backup_world_" + timestamp + ".zip";
		Path zipFilePath = targetDir.resolve(zipFileName);
		Path tmpZipPath = targetDir.resolve(zipFileName + ".tmp");

		LOGGER.info("[backup] Starting world backup to: {}", zipFilePath);
		long startTime = System.currentTimeMillis();

		Path normalizedSource = sourceDir.toAbsolutePath().normalize();
		Path normalizedTarget = targetDir.toAbsolutePath().normalize();

		long filesCount = 0;
		try (OutputStream fos = Files.newOutputStream(tmpZipPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			 ZipOutputStream zos = new ZipOutputStream(fos)) {

			zos.setLevel(5); // balanced compression speed & ratio

			List<Path> fileList = new ArrayList<>();
			Files.walkFileTree(normalizedSource, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					Path normDir = dir.toAbsolutePath().normalize();
					// Prevent recursion if targetDir is inside sourceDir
					if (normDir.startsWith(normalizedTarget)) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					Path normFile = file.toAbsolutePath().normalize();
					// Skip target backup directory and session.lock (which might be locked)
					if (normFile.startsWith(normalizedTarget)) {
						return FileVisitResult.CONTINUE;
					}
					if (file.getFileName() != null && file.getFileName().toString().equals("session.lock")) {
						return FileVisitResult.CONTINUE;
					}
					fileList.add(normFile);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) {
					LOGGER.warn("[backup] Could not access file {}: {}", file, exc != null ? exc.getMessage() : "unknown error");
					return FileVisitResult.CONTINUE;
				}
			});

			for (Path file : fileList) {
				String entryName = normalizedSource.relativize(file).toString().replace('\\', '/');
				try {
					ZipEntry entry = new ZipEntry(entryName);
					entry.setTime(Files.getLastModifiedTime(file).toMillis());
					zos.putNextEntry(entry);
					Files.copy(file, zos);
					zos.closeEntry();
					filesCount++;
				} catch (Exception e) {
					LOGGER.warn("[backup] Failed to zip file {}: {}", entryName, e.getMessage());
				}
			}

			zos.finish();
		} catch (Exception e) {
			LOGGER.error("[backup] Backup failed while creating zip: {}", e.getMessage(), e);
			try {
				Files.deleteIfExists(tmpZipPath);
			} catch (IOException ignored) {}
			return null;
		}

		try {
			Files.move(tmpZipPath, zipFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			try {
				Files.move(tmpZipPath, zipFilePath, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ex) {
				LOGGER.error("[backup] Failed to rename temp zip to final zip: {}", ex.getMessage());
				return null;
			}
		}

		long durationMs = System.currentTimeMillis() - startTime;
		long sizeBytes = 0;
		try {
			sizeBytes = Files.size(zipFilePath);
		} catch (IOException ignored) {}

		double sizeMb = sizeBytes / (1024.0 * 1024.0);
		LOGGER.info("[backup] World backup completed successfully! {} files archived ({} MB) in {} ms. Saved to: {}",
				filesCount, String.format("%.2f", sizeMb), durationMs, zipFilePath);

		rotateBackups(targetDir, maxBackups);
		return zipFilePath;
	}

	/**
	 * Deletes older backup files if count exceeds maxBackups.
	 */
	public static void rotateBackups(Path targetDir, int maxBackups) {
		if (maxBackups <= 0) return;
		try (var stream = Files.list(targetDir)) {
			List<Path> backups = stream
					.filter(p -> p.getFileName().toString().startsWith("backup_world_") && p.getFileName().toString().endsWith(".zip"))
					.sorted(Comparator.comparingLong(p -> {
						try {
							return Files.getLastModifiedTime(p).toMillis();
						} catch (IOException e) {
							return 0L;
						}
					}))
					.toList();

			if (backups.size() > maxBackups) {
				int toRemove = backups.size() - maxBackups;
				LOGGER.info("[backup] Found {} backups exceeding max limit of {}. Removing {} oldest backups...",
						backups.size(), maxBackups, toRemove);
				for (int i = 0; i < toRemove; i++) {
					Path old = backups.get(i);
					try {
						Files.deleteIfExists(old);
						LOGGER.info("[backup] Deleted old backup: {}", old.getFileName());
					} catch (IOException e) {
						LOGGER.warn("[backup] Could not delete old backup {}: {}", old.getFileName(), e.getMessage());
					}
				}
			}
		} catch (Exception e) {
			LOGGER.warn("[backup] Error rotating backup files: {}", e.getMessage());
		}
	}
}

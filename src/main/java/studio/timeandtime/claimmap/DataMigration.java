package studio.timeandtime.claimmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * One-time safety copy before an existing installation is upgraded.
 *
 * <p>The owner must never have to download, delete or recreate old files
 * (additional requirements, section A). Every existing data file is copied to
 * {@code config/timesmp/backups/pre-upgrade-<time>/} before the mod first
 * writes anything. A marker in {@code backups/} prevents repeating the copy on
 * every start.
 */
public final class DataMigration {
	private static final DateTimeFormatter STAMP =
			DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());

	private static final List<String> FILES = List.of(
			"timesmp.properties",
			"claimmap.properties",
			"accounts.json",
			"sessions.json",
			"regions.json",
			"economy.json",
			"ledger.jsonl",
			"prices-cache.json",
			"price-overrides.json",
			"mailbox.json",
			"shop-usage.json",
			"auctions.json",
			"player-market.json",
			"consignments.json",
			"positions.json",
			"audit.jsonl");

	private DataMigration() {
	}

	/**
	 * Copies old files once. Safe to call on every start; returns the created
	 * backup directory, or {@code null} when there was nothing to copy or the
	 * copy had already been done.
	 */
	public static synchronized Path backupBeforeUpgrade(Path dataDir) {
		if (dataDir == null) return null;
		try {
			if (!Files.isDirectory(dataDir)) return null;
			Path backups = dataDir.resolve("backups");
			Path marker = backups.resolve(".pre-upgrade-done");
			if (Files.exists(marker)) return null;

			boolean any = false;
			for (String name : FILES) {
				if (Files.isRegularFile(dataDir.resolve(name))) {
					any = true;
					break;
				}
			}
			if (!any) return null;

			Files.createDirectories(backups);
			Path target = backups.resolve("pre-upgrade-" + STAMP.format(Instant.now()));
			Files.createDirectories(target);
			for (String name : FILES) {
				Path source = dataDir.resolve(name);
				if (Files.isRegularFile(source)) {
					Files.copy(source, target.resolve(name), StandardCopyOption.REPLACE_EXISTING,
							StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
			Files.writeString(marker, Instant.now().toString());
			ClaimMap.LOGGER.info("[timesmp] Copied existing files to {} before the first upgrade", target);
			return target;
		} catch (IOException | RuntimeException e) {
			// Never continue with a migration when the safety copy failed.
			ClaimMap.LOGGER.error("[timesmp] Could not create the pre-upgrade backup in {}", dataDir, e);
			return null;
		}
	}
}

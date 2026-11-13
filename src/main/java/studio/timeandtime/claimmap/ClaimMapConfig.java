package studio.timeandtime.claimmap;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Properties;

/**
 * File based configuration stored in {@code config/claimmap/claimmap.properties}.
 * Deep risk: keep the HTTP surface bound to loopback and protect every endpoint
 * with a bearer token (see plan.md).
 */
public final class ClaimMapConfig {
	public static final String FILE_NAME = "config/claimmap/claimmap.properties";

	private final Path file;
	private final Properties props = new Properties();
	private final SecureRandom rng = new SecureRandom();

	public int port = 8085;
	public String bindAddress = "127.0.0.1";
	/** Master bearer token, generated once and printed on first start. */
	public String token = "";
	public int cellSize = 500;
	public int maxCellsPerPlayer = 3;
	/** Radius (in cells) around 0,0 treated as a common / spawn area (unclaimable). */
	public int spawnProtectedCells = 3;
	/** Map sampling step in blocks; one pixel per {@code renderScale} block. */
	public int renderScale = 4;
	/** Debounce delay for flushing map tiles to disk. */
	public long saveDelayMs = 15000;
	/** Validity of the 6 digit /link code. */
	public int linkCodeMinutes = 5;
	/** Validity of a web session token. */
	public int sessionHours = 24;

	public ClaimMapConfig() {
		this.file = FabricLoader.getInstance().getConfigDir().resolve("claimmap/claimmap.properties");
	}

	public void load() {
		if (Files.exists(file)) {
			try (InputStream in = Files.newInputStream(file)) {
				props.load(in);
			} catch (IOException e) {
				ClaimMap.LOGGER.error("[claimmap] Could not read config", e);
			}
		}
		port = getInt("port", port, 1, 65535);
		bindAddress = props.getProperty("bindAddress", bindAddress).trim();
		token = props.getProperty("token", "").trim();
		cellSize = getInt("cellSize", cellSize, 16, 10000);
		maxCellsPerPlayer = getInt("maxCellsPerPlayer", maxCellsPerPlayer, 1, 100000);
		spawnProtectedCells = getInt("spawnProtectedCells", spawnProtectedCells, 0, 100000);
		renderScale = getInt("renderScale", renderScale, 1, 100);
		saveDelayMs = getLong("saveDelayMs", saveDelayMs, 1000L, 3600000L);
		linkCodeMinutes = getInt("linkCodeMinutes", linkCodeMinutes, 1, 1440);
		sessionHours = getInt("sessionHours", sessionHours, 1, 8760);

		if (token.isEmpty()) {
			token = randomHex(32);
			props.setProperty("token", token);
			save();
			ClaimMap.LOGGER.info("[claimmap] Generated new master token. Keep it secret!");
			ClaimMap.LOGGER.info("[claimmap] Master token (Authorization: Bearer {} )", token);
		}
	}

	public void save() {
		props.setProperty("port", Integer.toString(port));
		props.setProperty("bindAddress", bindAddress);
		props.setProperty("token", token);
		props.setProperty("cellSize", Integer.toString(cellSize));
		props.setProperty("maxCellsPerPlayer", Integer.toString(maxCellsPerPlayer));
		props.setProperty("spawnProtectedCells", Integer.toString(spawnProtectedCells));
		props.setProperty("renderScale", Integer.toString(renderScale));
		props.setProperty("saveDelayMs", Long.toString(saveDelayMs));
		props.setProperty("linkCodeMinutes", Integer.toString(linkCodeMinutes));
		props.setProperty("sessionHours", Integer.toString(sessionHours));
		try {
			Path parent = file.getParent();
			if (parent != null) Files.createDirectories(parent);
			try (OutputStream out = Files.newOutputStream(file)) {
				props.store(out, "Claim Map configuration. Restart the server after editing.");
			}
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[claimmap] Could not write config", e);
		}
	}

	public Path tilesDir() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("claimmap/tiles");
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[claimmap] Could not create tiles dir", e);
		}
		return dir;
	}

	public Path regionsFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("claimmap/regions.json");
	}

	public Path sessionsFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("claimmap/sessions.json");
	}

	private int getInt(String key, int def, int min, int max) {
		int v = def;
		try {
			v = Integer.parseInt(props.getProperty(key, Integer.toString(def)).trim());
		} catch (NumberFormatException ignored) {
		}
		return Math.max(min, Math.min(max, v));
	}

	private long getLong(String key, long def, long min, long max) {
		long v = def;
		try {
			v = Long.parseLong(props.getProperty(key, Long.toString(def)).trim());
		} catch (NumberFormatException ignored) {
		}
		return Math.max(min, Math.min(max, v));
	}

	public static String randomHex(int bytes) {
		byte[] b = new byte[bytes];
		SecureRandom rnd = new SecureRandom();
		rnd.nextBytes(b);
		StringBuilder sb = new StringBuilder();
		for (byte x : b) {
			sb.append(String.format("%02x", x));
		}
		return sb.toString();
	}
}

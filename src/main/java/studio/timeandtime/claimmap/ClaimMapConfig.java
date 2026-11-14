package studio.timeandtime.claimmap;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * File based configuration stored in {@code config/claimmap/claimmap.properties}.
 *
 * <p>The HTTP API is self-hosted by the mod. {@code bindAddress} defaults to
 * {@code 0.0.0.0} so other machines on the LAN can reach it; the owner is
 * responsible for network security (plain HTTP by design, see PHASES.md B).
 */
public final class ClaimMapConfig {
	public static final String FILE_NAME = "config/claimmap/claimmap.properties";

	private final Path file;
	private final Properties props = new Properties();

	public int port = 8085;
	public String bindAddress = "0.0.0.0";
	/** Master bearer token, generated once and printed on first start. Script-only. */
	public String token = "";
	/** Comma separated whitelist of player names. Empty = no restriction. */
	public String players = "";
	/** Comma separated list of admin names. */
	public String admins = "";
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
	/** Display name of the currency. */
	public String currencyName = "เหรียญ";
	/** Money granted to a brand new player account. */
	public long startingBalance = 1000L;

	public ClaimMapConfig() {
		this(FabricLoader.getInstance().getConfigDir().resolve("claimmap/claimmap.properties"));
	}

	/** Test/seeded constructor: does not touch FabricLoader. */
	ClaimMapConfig(Path file) {
		this.file = file;
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
		String bind = props.getProperty("bindAddress");
		bindAddress = bind == null ? bindAddress : bind.trim();
		if (bindAddress.isEmpty()) bindAddress = "0.0.0.0";
		token = props.getProperty("token", "").trim();
		players = props.getProperty("players", players).trim();
		admins = props.getProperty("admins", admins).trim();
		cellSize = getInt("cellSize", cellSize, 16, 10000);
		maxCellsPerPlayer = getInt("maxCellsPerPlayer", maxCellsPerPlayer, 1, 100000);
		spawnProtectedCells = getInt("spawnProtectedCells", spawnProtectedCells, 0, 100000);
		renderScale = getInt("renderScale", renderScale, 1, 100);
		saveDelayMs = getLong("saveDelayMs", saveDelayMs, 1000L, 3600000L);
		linkCodeMinutes = getInt("linkCodeMinutes", linkCodeMinutes, 1, 1440);
		sessionHours = getInt("sessionHours", sessionHours, 1, 8760);
		currencyName = props.getProperty("currencyName", currencyName).trim();
		if (currencyName.isEmpty()) currencyName = "เหรียญ";
		startingBalance = getLong("startingBalance", startingBalance, 0L, 1_000_000_000_000_000L);

		boolean changed = false;
		if (token.isEmpty()) {
			token = randomHex(32);
			props.setProperty("token", token);
			changed = true;
			ClaimMap.LOGGER.info("[claimmap] Generated new master token. Keep it secret! (script-only, never embedded in the web page)");
		}
		// Surface the new keys in the owner's file so they can edit them.
		for (String key : new String[]{"players", "admins", "bindAddress", "currencyName", "startingBalance"}) {
			if (!props.containsKey(key)) {
				props.setProperty(key, switch (key) {
					case "players" -> players;
					case "admins" -> admins;
					case "currencyName" -> currencyName;
					case "startingBalance" -> Long.toString(startingBalance);
					default -> bindAddress;
				});
				changed = true;
			}
		}
		if (changed) save();

		if ("127.0.0.1".equals(bindAddress) || "localhost".equalsIgnoreCase(bindAddress)) {
			ClaimMap.LOGGER.info("[claimmap] bindAddress={} — other machines will NOT be able to open the web page. "
					+ "Change bindAddress to 0.0.0.0 in config/claimmap/claimmap.properties to allow LAN access.", bindAddress);
		}
	}

	public void save() {
		props.setProperty("port", Integer.toString(port));
		props.setProperty("bindAddress", bindAddress);
		props.setProperty("token", token);
		props.setProperty("players", players);
		props.setProperty("admins", admins);
		props.setProperty("cellSize", Integer.toString(cellSize));
		props.setProperty("maxCellsPerPlayer", Integer.toString(maxCellsPerPlayer));
		props.setProperty("spawnProtectedCells", Integer.toString(spawnProtectedCells));
		props.setProperty("renderScale", Integer.toString(renderScale));
		props.setProperty("saveDelayMs", Long.toString(saveDelayMs));
		props.setProperty("linkCodeMinutes", Integer.toString(linkCodeMinutes));
		props.setProperty("sessionHours", Integer.toString(sessionHours));
		props.setProperty("currencyName", currencyName);
		props.setProperty("startingBalance", Long.toString(startingBalance));
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

	/** True when {@code name} is listed in {@code admins} (case-insensitive, trimmed). */
	public boolean isAdmin(String name) {
		return containsName(admins, name);
	}

	/**
	 * True when {@code name} may use the server: admins always pass, an empty
	 * {@code players} list means "no restriction", otherwise the name must be listed.
	 */
	public boolean isAllowed(String name) {
		if (name == null || name.isBlank()) return false;
		if (isAdmin(name)) return true;
		if (parseNames(players).isEmpty()) return true;
		return containsName(players, name);
	}

	static List<String> parseNames(String csv) {
		List<String> out = new ArrayList<>();
		if (csv == null) return out;
		for (String part : csv.split(",")) {
			String n = part.trim();
			if (!n.isEmpty()) out.add(n.toLowerCase(Locale.ROOT));
		}
		return out;
	}

	private static boolean containsName(String csv, String name) {
		if (name == null || name.isBlank()) return false;
		String needle = name.trim().toLowerCase(Locale.ROOT);
		for (String n : parseNames(csv)) {
			if (n.equals(needle)) return true;
		}
		return false;
	}

	public Path tilesDir() {
		Path dir = dir().resolve("tiles");
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[claimmap] Could not create tiles dir", e);
		}
		return dir;
	}

	public Path regionsFile() {
		return dir().resolve("regions.json");
	}

	public Path sessionsFile() {
		return dir().resolve("sessions.json");
	}

	public Path accountsFile() {
		return dir().resolve("accounts.json");
	}

	/** Directory that holds every data file ({@code config/claimmap}). */
	public Path dataDir() {
		return dir();
	}

	private Path dir() {
		return file.getParent();
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

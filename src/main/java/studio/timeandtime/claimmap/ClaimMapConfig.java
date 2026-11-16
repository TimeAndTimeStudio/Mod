package studio.timeandtime.claimmap;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * File based configuration stored in {@code config/timesmp/timesmp.properties}.
 *
 * <p>The HTTP API is self-hosted by the mod. {@code bindAddress} defaults to
 * {@code 0.0.0.0} so other machines on the LAN can reach it; the owner is
 * responsible for network security (plain HTTP by design, see PHASES.md B).
 */
public final class ClaimMapConfig {
	public static final String FILE_NAME = "config/timesmp/timesmp.properties";

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
	public int freeCellsPerPlayer = 4;
	public int maxCellsPerPlayer = 0; // 0 = unlimited
	/** Radius (in cells) around 0,0 treated as a common / spawn area (unclaimable). 0 = disabled. */
	public int spawnProtectedCells = 0;
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
	public long startingBalance = 0L;
	/** PBKDF2 work factor for in-game / web passwords. */
	public int pbkdf2Iterations = 210_000;
	/** Remote price API endpoint + access key (optional; empty by default to use local prices.json). */
	public String pricesUrl = "";
	public String pricesAccessKey = "";
	public int priceRefreshSeconds = 60;
	/** Shop percentages of the base price (integers, PHASES.md 4.2). */
	public int sellPercent = 70;
	public int buyPercent = 130;
	/** Items that can never be bought, sold or auctioned. */
	public String blockedItems = "bedrock,command_block,barrier";
	/** Maximum items per shop transaction (36 stacks x 64). */
	public int shopMaxQtyPerTx = 2304;
	/** Per-player, per-item daily buy quota. 0 = unlimited. */
	public int shopDailyBuyQty = 0;
	/** Maximum entries in one player's mailbox. */
	public int mailboxMaxEntries = 500;
	/** Auction house (PHASES.md 6.2). */
	public int auctionMaxActivePerPlayer = 5;
	public int auctionDefaultHours = 24;
	public int auctionMaxHours = 72;
	public int auctionFeePercent = 5;
	public int auctionListFeePercent = 0;
	public int auctionMinIncrementPercent = 5;
	public int auctionAntiSnipeSeconds = 60;
	/** Maximum simultaneous fixed-price listings per player in the player market. */
	public int marketMaxActivePerPlayer = 10;
	/** Folder (relative to the server root) containing independent world saves. */
	public String worldDirectory = "worlds";
	/** How often the worlds folder is rescanned while the server runs. */
	public int worldScanSeconds = 30;
	/** Idle time before an empty loaded world is unloaded; 0 keeps it loaded. */
	public int worldIdleUnloadSeconds = 0;
	/** Expose one short command per discovered world, e.g. /adventure. */
	public boolean worldPlayerCommands = true;

	public ClaimMapConfig() {
		this(resolveConfigPath());
	}

	private static Path resolveConfigPath() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Path newPath = configDir.resolve("timesmp/timesmp.properties");
		Path legacyPath = configDir.resolve("claimmap/claimmap.properties");
		if (!Files.exists(newPath) && Files.exists(legacyPath)) {
			try {
				Path legacyDir = configDir.resolve("claimmap");
				Path newDir = configDir.resolve("timesmp");
				if (!Files.exists(newDir)) {
					Files.createDirectories(newDir);
					try (var stream = Files.list(legacyDir)) {
						for (Path f : stream.toList()) {
							if (Files.isRegularFile(f)) {
								Path dest = f.getFileName().toString().equals("claimmap.properties")
										? newDir.resolve("timesmp.properties")
										: newDir.resolve(f.getFileName());
								Files.copy(f, dest, StandardCopyOption.REPLACE_EXISTING);
							}
						}
					}
				}
				return newPath;
			} catch (Exception ignored) {
				return legacyPath;
			}
		}
		return newPath;
	}

	/** Test/seeded constructor: does not touch FabricLoader. */
	ClaimMapConfig(Path file) {
		this.file = file;
	}

	public void load() {
		// Back up existing data before the first write of an upgraded install.
		DataMigration.backupBeforeUpgrade(file.getParent());
		if (Files.exists(file)) {
			try (InputStream in = Files.newInputStream(file)) {
				props.load(in);
			} catch (IOException e) {
				ClaimMap.LOGGER.error("[timesmp] Could not read config", e);
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
		freeCellsPerPlayer = getInt("freeCellsPerPlayer", freeCellsPerPlayer, 0, 100000);
		maxCellsPerPlayer = getInt("maxCellsPerPlayer", maxCellsPerPlayer, 0, 100000);
		spawnProtectedCells = getInt("spawnProtectedCells", spawnProtectedCells, 0, 100000);
		renderScale = getInt("renderScale", renderScale, 1, 100);
		saveDelayMs = getLong("saveDelayMs", saveDelayMs, 1000L, 3600000L);
		linkCodeMinutes = getInt("linkCodeMinutes", linkCodeMinutes, 1, 1440);
		sessionHours = getInt("sessionHours", sessionHours, 1, 8760);
		currencyName = props.getProperty("currencyName", currencyName).trim();
		if (currencyName.isEmpty()) currencyName = "เหรียญ";
		startingBalance = getLong("startingBalance", startingBalance, 0L, 1_000_000_000_000_000L);
		pbkdf2Iterations = getInt("pbkdf2Iterations", pbkdf2Iterations, 10_000, 2_000_000);
		pricesUrl = props.getProperty("pricesUrl", pricesUrl).trim();
		pricesAccessKey = props.getProperty("pricesAccessKey", pricesAccessKey).trim();
		priceRefreshSeconds = getInt("priceRefreshSeconds", priceRefreshSeconds, 5, 86_400);
		blockedItems = props.getProperty("blockedItems", blockedItems).trim();
		shopMaxQtyPerTx = getInt("shopMaxQtyPerTx", shopMaxQtyPerTx, 1, 1_000_000);
		shopDailyBuyQty = getInt("shopDailyBuyQty", shopDailyBuyQty, 0, 1_000_000);
		mailboxMaxEntries = getInt("mailboxMaxEntries", mailboxMaxEntries, 1, 100_000);
		auctionMaxActivePerPlayer = getInt("auctionMaxActivePerPlayer", auctionMaxActivePerPlayer, 1, 1000);
		auctionDefaultHours = getInt("auctionDefaultHours", auctionDefaultHours, 1, 720);
		auctionMaxHours = getInt("auctionMaxHours", auctionMaxHours, 1, 720);
		if (auctionDefaultHours > auctionMaxHours) auctionDefaultHours = auctionMaxHours;
		auctionFeePercent = getInt("auctionFeePercent", auctionFeePercent, 0, 100);
		auctionListFeePercent = getInt("auctionListFeePercent", auctionListFeePercent, 0, 100);
		auctionMinIncrementPercent = getInt("auctionMinIncrementPercent", auctionMinIncrementPercent, 1, 1000);
		auctionAntiSnipeSeconds = getInt("auctionAntiSnipeSeconds", auctionAntiSnipeSeconds, 0, 3600);
		marketMaxActivePerPlayer = getInt("marketMaxActivePerPlayer", marketMaxActivePerPlayer, 1, 1000);
		worldDirectory = props.getProperty("worldDirectory", worldDirectory).trim();
		if (worldDirectory.isEmpty()) worldDirectory = "worlds";
		worldScanSeconds = getInt("worldScanSeconds", worldScanSeconds, 5, 86_400);
		worldIdleUnloadSeconds = getInt("worldIdleUnloadSeconds", worldIdleUnloadSeconds, 0, 86_400);
		worldPlayerCommands = getBool("worldPlayerCommands", worldPlayerCommands);

		int sell = getInt("sellPercent", sellPercent, 1, 100);
		int buy = getInt("buyPercent", buyPercent, 100, 100_000);
		if (!PriceMath.validPercents(sell, buy)) {
			ClaimMap.LOGGER.warn("[timesmp] sellPercent={} buyPercent={} is invalid (need 1<=sell<=100, buy>=100, sell<buy); "
					+ "falling back to 70/130", sell, buy);
			sell = 70;
			buy = 130;
		}
		sellPercent = sell;
		buyPercent = buy;

		boolean changed = false;
		if (token.isEmpty()) {
			token = randomHex(32);
			props.setProperty("token", token);
			changed = true;
			ClaimMap.LOGGER.info("[timesmp] Generated new master token. Keep it secret! (script-only, never embedded in the web page)");
		}
		// Surface the new keys in the owner's file so they can edit them.
		for (String key : new String[]{"players", "admins", "bindAddress", "currencyName", "startingBalance",
				"pbkdf2Iterations", "pricesUrl", "pricesAccessKey", "priceRefreshSeconds", "sellPercent",
				"buyPercent", "blockedItems", "shopMaxQtyPerTx", "shopDailyBuyQty", "mailboxMaxEntries",
				"auctionMaxActivePerPlayer", "auctionDefaultHours", "auctionMaxHours", "auctionFeePercent",
				"auctionListFeePercent", "auctionMinIncrementPercent", "auctionAntiSnipeSeconds",
				"marketMaxActivePerPlayer", "worldDirectory", "worldScanSeconds", "worldIdleUnloadSeconds",
				"worldPlayerCommands"}) {
			if (!props.containsKey(key)) {
				props.setProperty(key, switch (key) {
					case "players" -> players;
					case "admins" -> admins;
					case "currencyName" -> currencyName;
					case "startingBalance" -> Long.toString(startingBalance);
					case "pbkdf2Iterations" -> Integer.toString(pbkdf2Iterations);
					case "pricesUrl" -> pricesUrl;
					case "pricesAccessKey" -> pricesAccessKey;
					case "priceRefreshSeconds" -> Integer.toString(priceRefreshSeconds);
					case "sellPercent" -> Integer.toString(sellPercent);
					case "buyPercent" -> Integer.toString(buyPercent);
					case "blockedItems" -> blockedItems;
					case "shopMaxQtyPerTx" -> Integer.toString(shopMaxQtyPerTx);
					case "shopDailyBuyQty" -> Integer.toString(shopDailyBuyQty);
					case "mailboxMaxEntries" -> Integer.toString(mailboxMaxEntries);
					case "auctionMaxActivePerPlayer" -> Integer.toString(auctionMaxActivePerPlayer);
					case "auctionDefaultHours" -> Integer.toString(auctionDefaultHours);
					case "auctionMaxHours" -> Integer.toString(auctionMaxHours);
					case "auctionFeePercent" -> Integer.toString(auctionFeePercent);
					case "auctionListFeePercent" -> Integer.toString(auctionListFeePercent);
					case "auctionMinIncrementPercent" -> Integer.toString(auctionMinIncrementPercent);
					case "auctionAntiSnipeSeconds" -> Integer.toString(auctionAntiSnipeSeconds);
					case "marketMaxActivePerPlayer" -> Integer.toString(marketMaxActivePerPlayer);
					case "worldDirectory" -> worldDirectory;
					case "worldScanSeconds" -> Integer.toString(worldScanSeconds);
					case "worldIdleUnloadSeconds" -> Integer.toString(worldIdleUnloadSeconds);
					case "worldPlayerCommands" -> Boolean.toString(worldPlayerCommands);
					default -> bindAddress;
				});
				changed = true;
			}
		}
		if (changed) save();

		if ("127.0.0.1".equals(bindAddress) || "localhost".equalsIgnoreCase(bindAddress)) {
			ClaimMap.LOGGER.info("[timesmp] bindAddress={} — other machines will NOT be able to open the web page. "
					+ "Change bindAddress to 0.0.0.0 in config/timesmp/timesmp.properties to allow LAN access.", bindAddress);
		}
	}

	/**
	 * Reloads the editable values without changing the listening socket
	 * (PHASES.md 7.3): {@code port}, {@code bindAddress} and the generated
	 * master token stay as they were until the next real restart.
	 * Returns false when the fixed keys were edited, so the caller can say so.
	 */
	public boolean reload() {
		int oldPort = port;
		String oldBind = bindAddress;
		String oldToken = token;
		load();
		boolean restartNeeded = port != oldPort || !bindAddress.equals(oldBind);
		port = oldPort;
		bindAddress = oldBind;
		token = oldToken;
		props.setProperty("port", Integer.toString(port));
		props.setProperty("bindAddress", bindAddress);
		props.setProperty("token", token);
		save();
		return !restartNeeded;
	}

	public void save() {
		props.setProperty("port", Integer.toString(port));
		props.setProperty("bindAddress", bindAddress);
		props.setProperty("token", token);
		props.setProperty("players", players);
		props.setProperty("admins", admins);
		props.setProperty("cellSize", Integer.toString(cellSize));
		props.setProperty("freeCellsPerPlayer", Integer.toString(freeCellsPerPlayer));
		props.setProperty("maxCellsPerPlayer", Integer.toString(maxCellsPerPlayer));
		props.setProperty("spawnProtectedCells", Integer.toString(spawnProtectedCells));
		props.setProperty("renderScale", Integer.toString(renderScale));
		props.setProperty("saveDelayMs", Long.toString(saveDelayMs));
		props.setProperty("linkCodeMinutes", Integer.toString(linkCodeMinutes));
		props.setProperty("sessionHours", Integer.toString(sessionHours));
		props.setProperty("currencyName", currencyName);
		props.setProperty("startingBalance", Long.toString(startingBalance));
		props.setProperty("pbkdf2Iterations", Integer.toString(pbkdf2Iterations));
		props.setProperty("pricesUrl", pricesUrl);
		props.setProperty("pricesAccessKey", pricesAccessKey);
		props.setProperty("priceRefreshSeconds", Integer.toString(priceRefreshSeconds));
		props.setProperty("sellPercent", Integer.toString(sellPercent));
		props.setProperty("buyPercent", Integer.toString(buyPercent));
		props.setProperty("blockedItems", blockedItems);
		props.setProperty("shopMaxQtyPerTx", Integer.toString(shopMaxQtyPerTx));
		props.setProperty("shopDailyBuyQty", Integer.toString(shopDailyBuyQty));
		props.setProperty("mailboxMaxEntries", Integer.toString(mailboxMaxEntries));
		props.setProperty("auctionMaxActivePerPlayer", Integer.toString(auctionMaxActivePerPlayer));
		props.setProperty("auctionDefaultHours", Integer.toString(auctionDefaultHours));
		props.setProperty("auctionMaxHours", Integer.toString(auctionMaxHours));
		props.setProperty("auctionFeePercent", Integer.toString(auctionFeePercent));
		props.setProperty("auctionListFeePercent", Integer.toString(auctionListFeePercent));
		props.setProperty("auctionMinIncrementPercent", Integer.toString(auctionMinIncrementPercent));
		props.setProperty("auctionAntiSnipeSeconds", Integer.toString(auctionAntiSnipeSeconds));
		props.setProperty("marketMaxActivePerPlayer", Integer.toString(marketMaxActivePerPlayer));
		props.setProperty("worldDirectory", worldDirectory);
		props.setProperty("worldScanSeconds", Integer.toString(worldScanSeconds));
		props.setProperty("worldIdleUnloadSeconds", Integer.toString(worldIdleUnloadSeconds));
		props.setProperty("worldPlayerCommands", Boolean.toString(worldPlayerCommands));
		try {
			Path parent = file.getParent();
			if (parent != null) Files.createDirectories(parent);
			try (OutputStream out = Files.newOutputStream(file)) {
				props.store(out, "Time SMP configuration. Restart the server after editing.");
			}
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[timesmp] Could not write config", e);
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
			ClaimMap.LOGGER.error("[timesmp] Could not create tiles dir", e);
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

	/** Directory that holds every data file ({@code config/timesmp}). */
	public Path dataDir() {
		return dir();
	}

	public Path mailboxFile() {
		return dir().resolve("mailbox.json");
	}

	public Path shopUsageFile() {
		return dir().resolve("shop-usage.json");
	}

	public Path auctionsFile() {
		return dir().resolve("auctions.json");
	}

	/** Directory holding one item-cache JSON file per player UUID. */
	public Path playerItemsDir() {
		return dir().resolve("player-items");
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

	private boolean getBool(String key, boolean def) {
		String raw = props.getProperty(key);
		if (raw == null) return def;
		raw = raw.trim();
		if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("yes") || raw.equals("1")) return true;
		if (raw.equalsIgnoreCase("false") || raw.equalsIgnoreCase("no") || raw.equals("0")) return false;
		return def;
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

package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Player accounts for the web UI login. A player sets their own password
 * in game with {@code /pass <password>}; they then log in on the web page
 * with their Minecraft name + that password.
 *
 * <p>Only JSON persistence: {@code config/claimmap/accounts.json}
 * (passwords are salted SHA-256, never stored in plain text).
 */
public final class AccountManager {
	public static final class Account {
		public String uuid;
		public String name;
		public String salt;    // hex
		public String hash;    // hex sha256(salt + password)
		public long createdAt;
		public long updatedAt;

		public Account() {
		}
	}

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final Path file;
	private final Map<String, Account> accounts = new LinkedHashMap<>(); // keyed by uuid
	private final Object lock = new Object();

	public AccountManager(ClaimMapConfig config) {
		this.file = config.accountsFile();
	}

	public void load() {
		synchronized (lock) {
			accounts.clear();
			if (file == null || !Files.exists(file)) return;
			try {
				String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				Wrapper w = gson.fromJson(json, Wrapper.class);
				if (w != null && w.accounts != null) {
					for (Account a : w.accounts) {
						if (a != null && a.uuid != null && a.hash != null) {
							accounts.put(a.uuid, a);
						}
					}
				}
			} catch (Exception e) {
				ClaimMap.LOGGER.error("[claimmap] Could not load accounts.json", e);
			}
		}
	}

	public void save() {
		synchronized (lock) {
			try {
				Path parent = file.getParent();
				if (parent != null) Files.createDirectories(parent);
				Wrapper w = new Wrapper();
				w.accounts = new ArrayList<>(accounts.values());
				String json = gson.toJson(w);
				Path tmp = file.resolveSibling("accounts.json.tmp");
				Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				ClaimMap.LOGGER.error("[claimmap] Could not save accounts.json", e);
			}
		}
	}

	/** Set (or change) the password for a player. Returns false if too short/long. */
	public boolean setPassword(String uuid, String name, String plain) {
		if (plain == null || plain.length() < 4 || plain.length() > 64) return false;
		synchronized (lock) {
			Account a = accounts.get(uuid);
			if (a == null) {
				a = new Account();
				a.uuid = uuid;
				a.createdAt = System.currentTimeMillis();
				accounts.put(uuid, a);
			}
			a.name = name;
			a.salt = ClaimMapConfig.randomHex(8);
			a.hash = sha256(a.salt + plain);
			a.updatedAt = System.currentTimeMillis();
			save();
			return true;
		}
	}

	public boolean hasPassword(String uuid) {
		synchronized (lock) {
			Account a = accounts.get(uuid);
			return a != null && a.hash != null;
		}
	}

	/** Verify by display name (case-insensitive). Returns the account or null. */
	public Account verify(String name, String plain) {
		if (name == null || plain == null) return null;
		synchronized (lock) {
			String n = name.trim();
			for (Account a : accounts.values()) {
				if (a.name != null && a.name.equalsIgnoreCase(n) && a.hash != null) {
					if (constantTimeEquals(sha256(a.salt + plain), a.hash)) return a;
				}
			}
			return null;
		}
	}

	/**
	 * Burns one hash computation so an unknown account takes about as long as a
	 * wrong password (login must not reveal which names exist).
	 */
	public void burnVerify(String plain) {
		sha256(randomSalt() + (plain == null ? "" : plain));
	}

	private static String randomSalt() {
		return ClaimMapConfig.randomHex(8);
	}

	private static boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null) return false;
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	private static String sha256(String s) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(d.length * 2);
			for (byte b : d) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static final class Wrapper {
		List<Account> accounts;
	}
}

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
import java.util.Optional;
import java.util.UUID;

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
	private final int pbkdf2Iterations;

	public AccountManager(ClaimMapConfig config) {
		this.file = config.accountsFile();
		this.pbkdf2Iterations = config.pbkdf2Iterations;
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

	/**
	 * Sets a password using PBKDF2. Callers that must stay off the server thread
	 * should hash on a worker and use {@link #setPasswordHash(String, String, String)}.
	 */
	public boolean setPassword(String uuid, String name, String plain) {
		if (uuid == null || name == null || plain == null || plain.length() < 6 || plain.length() > 64) return false;
		if (plain.equalsIgnoreCase(name)) return false;
		return setPasswordHash(uuid, name, PasswordHasher.hash(plain, pbkdf2Iterations));
	}

	/** Stores an already-computed PBKDF2 hash (never plain text). */
	public boolean setPasswordHash(String uuid, String name, String passwordHash) {
		if (uuid == null || name == null || passwordHash == null) return false;
		synchronized (lock) {
			Account a = accounts.get(uuid);
			if (a == null) {
				a = new Account();
				a.uuid = uuid;
				a.createdAt = System.currentTimeMillis();
				accounts.put(uuid, a);
			}
			a.name = name;
			a.salt = "";
			a.hash = passwordHash;
			a.updatedAt = System.currentTimeMillis();
			save();
			return true;
		}
	}

	/** UUID of the account with this display name, if it has a password. */
	public Optional<UUID> uuidOf(String name) {
		if (name == null || name.isBlank()) return Optional.empty();
		String n = name.trim();
		synchronized (lock) {
			for (Account a : accounts.values()) {
				if (a.name != null && a.name.equalsIgnoreCase(n) && a.hash != null && !a.hash.isEmpty()) {
					try {
						return Optional.of(UUID.fromString(a.uuid));
					} catch (RuntimeException e) {
						return Optional.empty();
					}
				}
			}
		}
		return Optional.empty();
	}

	/** UUID by display name regardless of whether a password is set (admin use). */
	public Optional<UUID> anyUuidOf(String name) {
		if (name == null || name.isBlank()) return Optional.empty();
		String n = name.trim();
		synchronized (lock) {
			for (Account a : accounts.values()) {
				if (a.name != null && a.name.equalsIgnoreCase(n)) {
					try {
						return Optional.of(UUID.fromString(a.uuid));
					} catch (RuntimeException e) {
						return Optional.empty();
					}
				}
			}
		}
		return Optional.empty();
	}

	public record Identity(String uuid, String name) {
	}

	/** Every known account (admin overview), newest name wins per uuid. */
	public List<Identity> identities() {
		synchronized (lock) {
			List<Identity> out = new ArrayList<>();
			for (Account a : accounts.values()) {
				out.add(new Identity(a.uuid, a.name == null ? "" : a.name));
			}
			return out;
		}
	}

	/** Current display name for a UUID, if the account exists. */
	public Optional<String> nameOf(UUID uuid) {
		if (uuid == null) return Optional.empty();
		synchronized (lock) {
			Account a = accounts.get(uuid.toString());
			return a == null || a.name == null ? Optional.empty() : Optional.of(a.name);
		}
	}

	public boolean hasPassword(String uuid) {
		synchronized (lock) {
			Account a = accounts.get(uuid);
			return a != null && a.hash != null && !a.hash.isEmpty();
		}
	}

	/**
	 * Forgets the password of one account (admin reset, PHASES.md 7.3).
	 * The name is kept so the player is told to {@code /register} again.
	 */
	public boolean resetPassword(String uuid) {
		if (uuid == null) return false;
		synchronized (lock) {
			Account a = accounts.get(uuid);
			if (a == null) return false;
			a.salt = "";
			a.hash = "";
			a.updatedAt = System.currentTimeMillis();
			save();
			return true;
		}
	}

	/**
	 * Verify by display name (case-insensitive). Returns the account or null.
	 * A legacy salted SHA-256 hash is upgraded to PBKDF2 on a successful login.
	 */
	public Account verify(String name, String plain) {
		if (name == null || plain == null) return null;
		synchronized (lock) {
			String n = name.trim();
			for (Account a : accounts.values()) {
				if (a.name != null && a.name.equalsIgnoreCase(n) && a.hash != null && !a.hash.isEmpty()) {
					if (!PasswordHasher.verify(a.hash, a.salt, plain)) return null;
					if (!PasswordHasher.isModern(a.hash)) {
						a.hash = PasswordHasher.hash(plain, pbkdf2Iterations);
						a.salt = "";
						a.updatedAt = System.currentTimeMillis();
						save();
						ClaimMap.LOGGER.info("[claimmap] Upgraded stored password hash for {}", a.name);
					}
					return a;
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

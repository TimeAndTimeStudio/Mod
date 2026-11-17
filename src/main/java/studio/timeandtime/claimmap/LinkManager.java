package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binds a web session to a Minecraft account.
 *
 * <p>Two ways to obtain a session token:
 * <ol>
 *   <li>{@link #createSession} - password based: the in-game auth dialog sets
 *       a password, then {@code POST /api/login} on the web page exchanges
 *       name+password for a session token.</li>
 *   <li>{@link #issueCode}/{@link #exchange} - legacy 6 digit in-game code.</li>
 * </ol>
 *
 * <p>Sessions are persisted (JSON only) to {@code config/timesmp/sessions.json}
 * so they survive restarts.
 */
public final class LinkManager {
	public record Session(String token, String uuid, String name, long expiresAt) {
		public boolean valid(long now) {
			return now < expiresAt;
		}
	}

	private static final class Code {
		final String uuid;
		final String name;
		final long expiresAt;

		Code(String uuid, String name, long expiresAt) {
			this.uuid = uuid;
			this.name = name;
			this.expiresAt = expiresAt;
		}
	}

	private final Random rng = new SecureRandom();
	private final Map<String, Code> codes = new ConcurrentHashMap<>();
	private final Map<String, Session> sessions = new ConcurrentHashMap<>();
	private final ClaimMapConfig config;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final Path file;

	public LinkManager(ClaimMapConfig config) {
		this.config = config;
		this.file = config.sessionsFile();
	}

	public void load() {
		if (file == null || !Files.exists(file)) return;
		try {
			String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			SavedWrapper w = gson.fromJson(json, SavedWrapper.class);
			if (w == null || w.sessions == null) return;
			long now = System.currentTimeMillis();
			for (SavedSession s : w.sessions) {
				if (s != null && s.token != null && s.expiresAt > now) {
					sessions.putIfAbsent(s.token, new Session(s.token, s.uuid, s.name, s.expiresAt));
				}
			}
		} catch (Exception e) {
			ClaimMap.LOGGER.error("[timesmp] Could not load sessions.json", e);
		}
	}

	public String issueCode(String uuid, String name) {
		purge();
		String code;
		do {
			code = String.format("%06d", rng.nextInt(1_000_000));
		} while (codes.containsKey(code));
		long expires = System.currentTimeMillis() + config.linkCodeMinutes * 60_000L;
		codes.put(code, new Code(uuid, name, expires));
		return code;
	}

	public Session exchange(String code) {
		purge();
		Code c = codes.remove(code);
		if (c == null) return null;
		if (System.currentTimeMillis() >= c.expiresAt) return null;
		Session s = newSession(c.uuid, c.name);
		return s;
	}

	/** Create a session directly (password login). */
	public Session createSession(String uuid, String name) {
		purge();
		return newSession(uuid, name);
	}

	private Session newSession(String uuid, String name) {
		Session s = new Session(ClaimMapConfig.randomHex(24), uuid, name,
				System.currentTimeMillis() + config.sessionHours * 3_600_000L);
		sessions.put(s.token(), s);
		save();
		return s;
	}

	/** Drops every web session of an account (password change, admin reset). */
	public void revokeAllForUuid(String uuid) {
		if (uuid == null) return;
		boolean changed = sessions.entrySet().removeIf(e -> uuid.equals(e.getValue().uuid()));
		if (changed) save();
	}

	public Session resolve(String token) {
		purge();
		if (token == null) return null;
		return sessions.get(token);
	}

	private void save() {
		try {
			Path parent = file.getParent();
			if (parent != null) Files.createDirectories(parent);
			SavedWrapper w = new SavedWrapper();
			w.sessions = new ArrayList<>();
			for (Session s : sessions.values()) {
				SavedSession x = new SavedSession();
				x.token = s.token();
				x.uuid = s.uuid();
				x.name = s.name();
				x.expiresAt = s.expiresAt();
				w.sessions.add(x);
			}
			String json = gson.toJson(w);
			Path tmp = file.resolveSibling("sessions.json.tmp");
			Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			ClaimMap.LOGGER.error("[timesmp] Could not save sessions.json", e);
		}
	}

	private void purge() {
		long now = System.currentTimeMillis();
		codes.entrySet().removeIf(e -> now >= e.getValue().expiresAt);
		long before = sessions.size();
		sessions.entrySet().removeIf(e -> !e.getValue().valid(now));
		if (before != sessions.size()) save();
	}

	private static final class SavedSession {
		String token;
		String uuid;
		String name;
		long expiresAt;
	}

	private static final class SavedWrapper {
		List<SavedSession> sessions;
	}
}

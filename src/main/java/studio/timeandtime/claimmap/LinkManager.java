package studio.timeandtime.claimmap;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binds a web session to a Minecraft account via a short-lived 6 digit
 * in-game code (plan.md: ผูกบัญชีเว็บกับผู้เล่น).
 *
 * <ol>
 *   <li>Player runs {@code /link} in game to receive a 6 digit code.</li>
 *   <li>The code is exchanged on the web site for a session token bound to the player UUID.</li>
 * </ol>
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

	public LinkManager(ClaimMapConfig config) {
		this.config = config;
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
		String token = ClaimMapConfig.randomHex(24);
		long expires = System.currentTimeMillis() + config.sessionHours * 3_600_000L;
		Session s = new Session(token, c.uuid, c.name, expires);
		sessions.put(token, s);
		return s;
	}

	public Session resolve(String token) {
		purge();
		if (token == null) return null;
		return sessions.get(token);
	}

	private void purge() {
		long now = System.currentTimeMillis();
		codes.entrySet().removeIf(e -> now >= e.getValue().expiresAt);
		sessions.entrySet().removeIf(e -> !e.getValue().valid(now));
	}
}

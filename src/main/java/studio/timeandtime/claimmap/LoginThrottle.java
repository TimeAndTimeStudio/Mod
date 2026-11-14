package studio.timeandtime.claimmap;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Locks a (ip, player name) pair after {@value #MAX_FAILS} consecutive failed
 * login attempts. In-memory only; resets when the server restarts.
 */
final class LoginThrottle {
	static final int MAX_FAILS = 5;
	static final long LOCK_MS = 60_000L;
	/** Entries not touched for this long are dropped to keep memory bounded. */
	static final long PURGE_AFTER_MS = 15 * 60_000L;
	private static final int PURGE_THRESHOLD = 1000;

	private final ConcurrentHashMap<String, long[]> map = new ConcurrentHashMap<>();

	static String key(String ip, String name) {
		String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
		return (ip == null ? "?" : ip) + "|" + n;
	}

	/** Milliseconds the caller must wait, or 0 when not locked. */
	long retryAfterMs(String key, long now) {
		long[] st = map.get(key);
		if (st == null) return 0;
		synchronized (st) {
			long left = st[1] - now;
			return left > 0 ? left : 0;
		}
	}

	/** Records a failed attempt; the 5th failure starts a 60 second lock. */
	void fail(String key, long now) {
		purge(now);
		long[] st = map.computeIfAbsent(key, k -> new long[3]);
		synchronized (st) {
			// A lock that already expired starts counting from scratch.
			if (st[1] > 0 && now >= st[1]) {
				st[0] = 0;
				st[1] = 0;
			}
			st[2] = now;
			if (st[1] > now) return; // already locked: keep the lock, do not extend
			st[0]++;
			if (st[0] >= MAX_FAILS) {
				st[0] = 0;
				st[1] = now + LOCK_MS;
			}
		}
	}

	void success(String key) {
		map.remove(key);
	}

	int size() {
		return map.size();
	}

	private void purge(long now) {
		if (map.size() < PURGE_THRESHOLD) return;
		map.entrySet().removeIf(e -> {
			long[] st = e.getValue();
			synchronized (st) {
				return now - st[2] > PURGE_AFTER_MS && st[1] <= now;
			}
		});
	}
}

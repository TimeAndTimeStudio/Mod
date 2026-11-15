package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Append-only admin audit trail ({@code audit.jsonl}, PHASES.md 7.1).
 *
 * <p>One JSON object per line; every success and every rejection is written.
 * Callers must never put passwords, session tokens or the master token in
 * {@code details}.
 */
public final class AuditLog {
	private static final Gson GSON = new GsonBuilder().create();
	private static final int MAX_TAIL = 200;

	private final Path file;
	private final LongSupplier now;
	private final Object lock = new Object();

	public AuditLog(Path dir, LongSupplier now) {
		this.file = dir.resolve("audit.jsonl");
		this.now = now;
	}

	public void write(String actor, String action, Map<String, Object> details, String ip, boolean success) {
		JsonObject o = new JsonObject();
		o.addProperty("t", now.getAsLong());
		o.addProperty("actor", actor == null || actor.isBlank() ? "CONSOLE" : actor);
		o.addProperty("action", action == null ? "unknown" : action);
		o.add("details", GSON.toJsonTree(details == null ? Map.of() : new LinkedHashMap<>(details)));
		o.addProperty("ip", ip == null || ip.isBlank() ? null : ip);
		o.addProperty("ok", success);
		String line = GSON.toJson(o) + "\n";
		synchronized (lock) {
			try {
				Path parent = file.getParent();
				if (parent != null) Files.createDirectories(parent);
				Files.writeString(file, line, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
			} catch (IOException e) {
				ClaimMap.LOGGER.error("[timesmp] Could not append to audit.jsonl", e);
			}
		}
	}

	/** Newest lines last, at most {@value #MAX_TAIL}. */
	public List<String> tail(int limit) {
		int max = Math.max(0, Math.min(MAX_TAIL, limit));
		if (max == 0) return List.of();
		List<String> lines;
		synchronized (lock) {
			if (!Files.exists(file)) return List.of();
			try {
				lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			} catch (IOException e) {
				ClaimMap.LOGGER.error("[timesmp] Could not read audit.jsonl", e);
				return List.of();
			}
		}
		List<String> out = new ArrayList<>();
		for (int i = Math.max(0, lines.size() - max); i < lines.size(); i++) out.add(lines.get(i));
		return out;
	}
}

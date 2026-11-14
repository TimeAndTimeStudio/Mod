import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone money audit (PHASES.md phase 2/9). No dependencies so it runs with
 * the JDK alone:
 *
 * <pre>java tools/CheckLedger.java &lt;config/claimmap directory&gt;</pre>
 *
 * Checks: ledger replay == economy.json snapshot · players + escrow + systemNet = 0
 * · auction escrow matches the auction state (when auctions.json exists).
 */
public final class CheckLedger {
	private static final Pattern LAST_SEQ = Pattern.compile("\"lastSeq\"\\s*:\\s*(-?\\d+)");
	private static final Pattern SYSTEM_NET = Pattern.compile("\"systemNet\"\\s*:\\s*(-?\\d+)");
	private static final Pattern PAIR = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(-?\\d+)");
	private static final Pattern SEQ = Pattern.compile("\"seq\"\\s*:\\s*(\\d+)");
	private static final Pattern FROM = Pattern.compile("\"from\"\\s*:\\s*\"([^\"]*)\"");
	private static final Pattern TO = Pattern.compile("\"to\"\\s*:\\s*\"([^\"]*)\"");
	private static final Pattern AMOUNT = Pattern.compile("\"amount\"\\s*:\\s*(\\d+)");

	private static final List<String> failures = new ArrayList<>();

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("usage: java tools/CheckLedger.java <config/claimmap dir>");
			System.exit(2);
		}
		Path dir = Path.of(args[0]);
		Path snapshotFile = dir.resolve("economy.json");
		Path ledgerFile = dir.resolve("ledger.jsonl");

		Map<String, Long> snapBalances = new LinkedHashMap<>();
		Map<String, Long> snapEscrow = new LinkedHashMap<>();
		long snapLastSeq = 0;
		long snapSystemNet = 0;

		if (Files.exists(snapshotFile)) {
			String json = Files.readString(snapshotFile, StandardCharsets.UTF_8);
			snapLastSeq = longOf(LAST_SEQ, json, 0);
			snapSystemNet = longOf(SYSTEM_NET, json, 0);
			readObject(json, "balances", snapBalances);
			readObject(json, "escrow", snapEscrow);
		}

		Map<String, Long> balances = new LinkedHashMap<>();
		Map<String, Long> escrow = new LinkedHashMap<>();
		long[] systemNet = {0};
		long lastSeq = 0;

		if (Files.exists(ledgerFile)) {
			int lineNo = 0;
			for (String line : Files.readAllLines(ledgerFile, StandardCharsets.UTF_8)) {
				lineNo++;
				if (line.isBlank()) continue;
				try {
					long seq = longOf(SEQ, line, -1);
					String from = strOf(FROM, line);
					String to = strOf(TO, line);
					long amount = longOf(AMOUNT, line, -1);
					if (seq < 0 || from == null || to == null || amount < 1) {
						throw new IllegalArgumentException("missing field");
					}
					move(balances, escrow, systemNet, from, -amount);
					move(balances, escrow, systemNet, to, amount);
					lastSeq = Math.max(lastSeq, seq);
				} catch (RuntimeException e) {
					fail("ledger line " + lineNo + " unreadable (" + e.getMessage() + ")");
				}
			}
		}

		compare("balance", snapBalances, balances);
		compare("escrow", snapEscrow, escrow);
		if (snapSystemNet != systemNet[0]) {
			fail("systemNet snapshot=" + snapSystemNet + " ledger=" + systemNet[0]);
		}
		if (snapLastSeq != lastSeq) {
			fail("lastSeq snapshot=" + snapLastSeq + " ledger=" + lastSeq);
		}

		long total = 0;
		for (long v : balances.values()) {
			total += v;
			if (v < 0) fail("negative balance: " + v);
		}
		for (long v : escrow.values()) {
			total += v;
			if (v < 0) fail("negative escrow: " + v);
		}
		total += systemNet[0];
		if (total != 0) {
			fail("invariant broken: players + escrow + systemNet = " + total);
		}

		checkAuctions(dir, escrow);

		if (failures.isEmpty()) {
			System.out.println("PASS: ledger replay matches the snapshot");
			System.out.println("  accounts=" + balances.size() + " escrowPots=" + escrow.size()
					+ " lastSeq=" + lastSeq + " systemNet=" + systemNet[0] + " invariant=0");
		} else {
			System.out.println("FAIL:");
			for (String f : failures) System.out.println("  - " + f);
			System.exit(1);
		}
	}

	/** Replays auctions.json: ACTIVE must hold exactly highBid in escrow, closed must hold 0. */
	private static void checkAuctions(Path dir, Map<String, Long> escrow) throws IOException {
		Path file = dir.resolve("auctions.json");
		if (!Files.exists(file)) return;
		String json;
		try {
			json = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			fail("auctions.json unreadable: " + e.getMessage());
			return;
		}
		Matcher id = Pattern.compile("\"id\"\\s*:\\s*\"(au_[^\"]+)\"").matcher(json);
		while (id.find()) {
			String auctionId = id.group(1);
			int start = id.end();
			int end = json.indexOf("\"id\"", start);
			String row = end < 0 ? json.substring(start) : json.substring(start, end);
			String status = valueOf(row, "status");
			long highBid = longOf(Pattern.compile("\"highBid\"\\s*:\\s*(\\d+)"), row, 0);
			long held = escrow.getOrDefault(auctionId, 0L);
			long expected = "ACTIVE".equals(status) ? highBid : 0L;
			if (held != expected) {
				fail("auction " + auctionId + " status=" + status + " escrow=" + held + " expected=" + expected);
			}
		}
	}

	private static void move(Map<String, Long> balances, Map<String, Long> escrow, long[] systemNet,
			String acct, long delta) {
		if ("SYSTEM".equals(acct)) {
			systemNet[0] += delta;
		} else if (acct.startsWith("P:")) {
			balances.merge(acct.substring(2), delta, Long::sum);
		} else if (acct.startsWith("E:")) {
			escrow.merge(acct.substring(2), delta, Long::sum);
		} else {
			throw new IllegalArgumentException("unknown account '" + acct + "'");
		}
	}

	private static void readObject(String json, String name, Map<String, Long> out) {
		int idx = json.indexOf("\"" + name + "\"");
		if (idx < 0) return;
		int open = json.indexOf('{', idx);
		if (open < 0) return;
		int close = json.indexOf('}', open);
		if (close < 0) return;
		Matcher m = PAIR.matcher(json.substring(open + 1, close));
		while (m.find()) out.put(m.group(1), Long.parseLong(m.group(2)));
	}

	private static String valueOf(String json, String field) {
		Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
		return m.find() ? m.group(1) : null;
	}

	private static String strOf(Pattern p, String s) {
		Matcher m = p.matcher(s);
		return m.find() ? m.group(1) : null;
	}

	private static long longOf(Pattern p, String s, long def) {
		Matcher m = p.matcher(s);
		return m.find() ? Long.parseLong(m.group(1)) : def;
	}

	private static void compare(String what, Map<String, Long> snap, Map<String, Long> live) {
		for (Map.Entry<String, Long> e : live.entrySet()) {
			long expected = snap.getOrDefault(e.getKey(), 0L);
			if (expected != e.getValue()) {
				fail(what + " " + e.getKey() + " snapshot=" + expected + " ledger=" + e.getValue());
			}
		}
		for (String key : snap.keySet()) {
			if (!live.containsKey(key)) fail(what + " " + key + " missing from ledger replay");
		}
	}

	private static void fail(String message) {
		failures.add(message);
	}

	private CheckLedger() {
	}
}

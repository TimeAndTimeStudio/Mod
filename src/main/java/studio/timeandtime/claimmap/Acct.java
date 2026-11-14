package studio.timeandtime.claimmap;

import java.util.UUID;

/**
 * One side of a money movement. Either a player wallet ({@code P:<uuid>}), the
 * system account ({@code SYSTEM}) or an escrow pot ({@code E:<escrowId>}).
 * The string form is what gets written to the ledger.
 */
public record Acct(String kind, String id) {
	public static final Acct SYSTEM = new Acct("SYSTEM", "");

	public static Acct player(UUID uuid) {
		return new Acct("P", uuid.toString());
	}

	public static Acct escrow(String escrowId) {
		return new Acct("E", escrowId);
	}

	public boolean isPlayer() {
		return "P".equals(kind);
	}

	public boolean isEscrow() {
		return "E".equals(kind);
	}

	public boolean isSystem() {
		return "SYSTEM".equals(kind);
	}

	/** UUID for player accounts; null for SYSTEM / escrow. */
	public UUID uuid() {
		if (!isPlayer()) return null;
		try {
			return UUID.fromString(id);
		} catch (RuntimeException e) {
			return null;
		}
	}

	@Override
	public String toString() {
		if (isSystem()) return "SYSTEM";
		return kind + ":" + id;
	}

	/** Parses the canonical ledger form; null when malformed. */
	public static Acct parse(String s) {
		if (s == null) return null;
		if (s.equals("SYSTEM")) return SYSTEM;
		int colon = s.indexOf(':');
		if (colon <= 0 || colon == s.length() - 1) return null;
		String kind = s.substring(0, colon);
		String id = s.substring(colon + 1);
		if (kind.equals("P")) {
			try {
				UUID.fromString(id);
			} catch (RuntimeException e) {
				return null;
			}
			return new Acct("P", id);
		}
		if (kind.equals("E")) return new Acct("E", id);
		return null;
	}
}

package studio.timeandtime.claimmap;

/**
 * A single money movement inside one atomic batch. {@code reason} uses the
 * standard codes listed in PHASES.md 2.5; {@code ref} links related rows.
 */
public record Move(Acct from, Acct to, long amount, String reason, String ref) {
	public Move {
		if (reason == null) reason = "";
		if (ref == null) ref = "";
	}
}

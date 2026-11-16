package studio.timeandtime.claimmap;

import java.util.Locale;
import java.util.Set;

/**
 * Filter that determines which commands are visible and executable for regular players.
 * Regular players only see and can execute mod commands and whisper commands (/msg, /tell, /w).
 */
public final class CommandFilter {

	private CommandFilter() {}

	public static final Set<String> ALLOWED_MOD_COMMANDS = Set.of(
			"pass", "bal", "pay", "sell", "buy", "mail", "ah",
			"claim", "claimmap", "timesmp", "main", "world",
			"tp", "tpa", "tpaccept", "tpdeny", "tpacancel"
	);

	public static final Set<String> ALLOWED_WHISPER_COMMANDS = Set.of(
			"msg", "tell", "w"
	);

	public static boolean isAllowedCommand(String name, ClaimMap mod) {
		if (name == null || name.isBlank()) return false;
		String lower = name.toLowerCase(Locale.ROOT);
		if (ALLOWED_WHISPER_COMMANDS.contains(lower) || ALLOWED_MOD_COMMANDS.contains(lower)) {
			return true;
		}
		// Dynamic world commands from WorldManager
		if (mod != null && mod.worldManager() != null) {
			for (var info : mod.worldManager().snapshot()) {
				if (info.name().equalsIgnoreCase(lower)) {
					return true;
				}
			}
		}
		return false;
	}
}

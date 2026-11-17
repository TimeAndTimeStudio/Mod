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
			"bal", "pay", "sell", "buy", "mail", "ah", "price",
			"claim", "timesmp", "map", "help", "smphelp", "menu",
			"sethome", "delhome", "home",
			"tpa", "tpaccept", "tpdeny", "tpacancel",
			"main"
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
		ClaimMap m = mod != null ? mod : ClaimMap.instance();
		if (m != null && m.worldManager() != null) {
			for (var info : m.worldManager().snapshot()) {
				if (info.name().equalsIgnoreCase(lower)) {
					return true;
				}
			}
		}
		return false;
	}
}

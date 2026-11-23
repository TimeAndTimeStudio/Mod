package studio.timeandtime.claimmap.menu;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Parsed definition of a DeluxeMenus-style custom GUI menu.
 */
public record CustomMenuDefinition(
		String id,
		String title,
		int size,
		List<String> openCommands,
		Map<String, MenuItemDefinition> items
) {
	public int getRows() {
		return Math.max(1, Math.min(6, size / 9));
	}
}

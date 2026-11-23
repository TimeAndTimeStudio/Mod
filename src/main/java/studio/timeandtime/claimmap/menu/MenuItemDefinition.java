package studio.timeandtime.claimmap.menu;

import java.util.List;

/**
 * Definition of an item inside a CustomMenuDefinition.
 */
public record MenuItemDefinition(
		String key,
		String material,
		int slot,
		List<Integer> slots,
		String displayName,
		List<String> lore,
		int customModelData,
		String customModelType, // "cart", "brand", "coin", etc. matching GuiButtons
		List<String> clickCommands,
		List<String> leftClickCommands,
		List<String> rightClickCommands
) {
}

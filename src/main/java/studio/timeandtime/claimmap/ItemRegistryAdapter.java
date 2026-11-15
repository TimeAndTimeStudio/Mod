package studio.timeandtime.claimmap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Answers "does this item id exist in this game version?" and holds display
 * names, so the HTTP thread never touches the live registry. Initialised once
 * on {@code SERVER_STARTED}, after which reads are plain map lookups.
 */
public final class ItemRegistryAdapter {
	private static volatile Set<String> ids;
	private static volatile Map<String, String> labels = Map.of();

	private ItemRegistryAdapter() {
	}

	public static void init(MinecraftServer server) {
		Map<String, String> names = new HashMap<>();
		for (Map.Entry<ResourceKey<Item>, Item> e : BuiltInRegistries.ITEM.entrySet()) {
			Identifier id = e.getKey().identifier();
			String label;
			try {
				label = new ItemStack(e.getValue()).getHoverName().getString();
			} catch (RuntimeException ex) {
				label = id.getPath();
			}
			names.put(id.toString(), label);
		}
		labels = Map.copyOf(names);
		ids = names.keySet();
		ClaimMap.LOGGER.info("[timesmp] Item registry snapshot: {} items", ids.size());
	}

	/** True when the id exists in the running game version. */
	public static boolean exists(String itemId) {
		Set<String> set = ids;
		return set != null && itemId != null && set.contains(itemId);
	}

	public static boolean ready() {
		return ids != null;
	}

	/** Human readable name, falling back to the id itself. */
	public static String label(String itemId) {
		Map<String, String> map = labels;
		String l = map.get(itemId);
		return l == null ? itemId : l;
	}
}

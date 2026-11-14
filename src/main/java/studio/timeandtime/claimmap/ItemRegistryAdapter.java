package studio.timeandtime.claimmap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import java.util.HashSet;
import java.util.Set;

/**
 * Answers "does this item id exist in this game version?" without touching the
 * registry from the HTTP thread at random times. Initialised once on
 * {@code SERVER_STARTED}, after which reads are a plain set lookup.
 */
public final class ItemRegistryAdapter {
	private static volatile Set<String> ids;

	private ItemRegistryAdapter() {
	}

	public static void init(MinecraftServer server) {
		Set<String> set = new HashSet<>();
		for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
			set.add(id.toString());
		}
		ids = Set.copyOf(set);
		ClaimMap.LOGGER.info("[claimmap] Item registry snapshot: {} items", ids.size());
	}

	/** True when the id exists in the running game version. */
	public static boolean exists(String itemId) {
		Set<String> set = ids;
		return set != null && itemId != null && set.contains(itemId);
	}

	public static boolean ready() {
		return ids != null;
	}
}

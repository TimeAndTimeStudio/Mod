package studio.timeandtime.claimmap;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * The only place that knows how to turn an {@link ItemStack} into the opaque
 * {@code itemJson} string stored in mailbox / auction files (PHASES.md 5.1).
 * Everything (enchantments, custom names, damage, components) is preserved.
 */
public final class ItemCodecs {
	/** Small display projection used by the web and auction listings. */
	public record ItemView(String itemId, int count, String label) {
	}

	private ItemCodecs() {
	}

	private static RegistryOps<JsonElement> ops(RegistryAccess access) {
		return RegistryOps.create(JsonOps.INSTANCE, access);
	}

	/** Serializes the full stack. Returns null when the game codec refuses it. */
	public static String encode(ItemStack stack, RegistryAccess access) {
		if (stack == null || stack.isEmpty()) return null;
		return ItemStack.CODEC.encodeStart(ops(access), stack)
				.result()
				.map(JsonElement::toString)
				.orElse(null);
	}

	public static Optional<ItemStack> decode(String json, RegistryAccess access) {
		if (json == null || json.isBlank()) return Optional.empty();
		try {
			JsonElement el = JsonParser.parseString(json);
			return ItemStack.CODEC.parse(ops(access), el).result();
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	public static ItemView view(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return new ItemView("minecraft:air", 0, "อากาศ");
		String itemId = idOf(stack);
		String label = stack.getHoverName().getString();
		if (stack.isEnchanted()) label = label + " (มีเสน่ห์)";
		return new ItemView(itemId, stack.getCount(), label);
	}

	public static String idOf(ItemStack stack) {
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return id == null ? "minecraft:air" : id.toString();
	}

	/**
	 * True when the stack has no components beyond the item defaults (no custom
	 * name, enchantments, damage, ...). The shop only buys plain items.
	 */
	public static boolean isPlain(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		DataComponentPatch patch = stack.getComponentsPatch();
		return patch == null || patch.isEmpty();
	}
}

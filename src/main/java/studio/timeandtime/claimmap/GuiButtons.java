package studio.timeandtime.claimmap;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;

public final class GuiButtons {
	private GuiButtons() {}

	public static final String CART = "cart";
	public static final String TAG = "tag";
	public static final String MAP = "map";
	public static final String BACK = "back";
	public static final String NEXT = "next";
	public static final String GAVEL = "gavel";
	public static final String PARCEL = "parcel";
	public static final String GEAR = "gear";
	public static final String COIN = "coin";
	public static final String CHAT = "chat";
	public static final String NAV_NORTH = "nav_north";
	public static final String NAV_SOUTH = "nav_south";
	public static final String NAV_WEST = "nav_west";
	public static final String NAV_EAST = "nav_east";
	public static final String NAV_CENTER = "nav_center";
	public static final String CLAIM = "claim";
	public static final String WARP = "warp";
	public static final String ABANDON = "abandon";
	public static final String BRAND = "brand";
	public static final String HELP = "help";

	public static int modelId(String buttonId) {
		return switch (buttonId) {
			case CART -> 1000;
			case TAG -> 1001;
			case MAP -> 1002;
			case BACK -> 1003;
			case NEXT -> 1004;
			case GAVEL -> 1005;
			case PARCEL -> 1006;
			case GEAR -> 1007;
			case COIN -> 1008;
			case CHAT -> 1009;
			case NAV_NORTH -> 1011;
			case NAV_SOUTH -> 1012;
			case NAV_WEST -> 1013;
			case NAV_EAST -> 1014;
			case NAV_CENTER -> 1015;
			case CLAIM -> 1016;
			case WARP -> 1017;
			case ABANDON -> 1018;
			case BRAND -> 1019;
			case HELP -> 1020;
			default -> 1000;
		};
	}

	public static ItemStack button(String buttonId, Component title, List<Component> lore) {
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.CUSTOM_NAME, title);
		if (lore != null && !lore.isEmpty()) {
			stack.set(DataComponents.LORE, new ItemLore(lore));
		}
		int id = modelId(buttonId);
		stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("minecraft", "button_" + buttonId));
		stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of((float) id), List.of(), List.of(), List.of()));
		return stack;
	}

	public static ItemStack button(String buttonId, String title, List<String> lore) {
		List<Component> loreComp = new java.util.ArrayList<>();
		if (lore != null) {
			for (String line : lore) {
				loreComp.add(Component.literal(line));
			}
		}
		return button(buttonId, Component.literal(title), loreComp);
	}
}

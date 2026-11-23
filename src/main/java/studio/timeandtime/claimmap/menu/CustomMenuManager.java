package studio.timeandtime.claimmap.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.timeandtime.claimmap.ClaimMap;
import studio.timeandtime.claimmap.GuiButtons;
import studio.timeandtime.claimmap.VirtualChestMenu;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DeluxeMenus-style custom GUI manager for Time SMP (Server-side, pure vanilla packet/chest GUI).
 * Reads JSON configuration files from config/timesmp/menus/*.json and registers open commands.
 */
public final class CustomMenuManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("timesmp-menus");

	private final Path menusDir;
	private final Map<String, CustomMenuDefinition> menus = new ConcurrentHashMap<>();
	private final Map<String, String> commandToMenuId = new ConcurrentHashMap<>();

	public CustomMenuManager(Path configDir) {
		this.menusDir = configDir.resolve("menus");
	}

	public void load() {
		menus.clear();
		commandToMenuId.clear();

		try {
			if (!Files.exists(menusDir)) {
				Files.createDirectories(menusDir);
				copyDefaultMenus();
			}

			try (DirectoryStream<Path> stream = Files.newDirectoryStream(menusDir, "*.json")) {
				for (Path file : stream) {
					loadMenuFile(file);
				}
			}
		} catch (Exception e) {
			LOGGER.error("[timesmp] Failed to load custom menus", e);
		}
		LOGGER.info("[timesmp] Loaded {} custom menus", menus.size());
	}

	private void copyDefaultMenus() {
		String[] defaults = { "main_menu.json", "warp_menu.json" };
		for (String name : defaults) {
			Path target = menusDir.resolve(name);
			if (!Files.exists(target)) {
				try (InputStream in = getClass().getResourceAsStream("/default-menus/" + name)) {
					if (in != null) {
						Files.copy(in, target);
						LOGGER.info("[timesmp] Extracted default menu: {}", name);
					}
				} catch (Exception e) {
					LOGGER.warn("[timesmp] Could not copy default menu {}", name, e);
				}
			}
		}
	}

	private void loadMenuFile(Path file) {
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			String id = file.getFileName().toString().replace(".json", "").toLowerCase();
			String title = json.has("menu_title") ? json.get("menu_title").getAsString() : (json.has("title") ? json.get("title").getAsString() : "&8Custom Menu");
			int size = json.has("size") ? json.get("size").getAsInt() : 27;
			if (size < 9 || size > 54 || size % 9 != 0) {
				size = 27;
			}

			List<String> openCmds = new ArrayList<>();
			if (json.has("open_command")) {
				JsonElement cmdElem = json.get("open_command");
				if (cmdElem.isJsonArray()) {
					for (JsonElement e : cmdElem.getAsJsonArray()) openCmds.add(e.getAsString().toLowerCase().trim());
				} else if (cmdElem.isJsonPrimitive()) {
					openCmds.add(cmdElem.getAsString().toLowerCase().trim());
				}
			}
			if (json.has("open_commands") && json.get("open_commands").isJsonArray()) {
				for (JsonElement e : json.getAsJsonArray("open_commands")) openCmds.add(e.getAsString().toLowerCase().trim());
			}

			Map<String, MenuItemDefinition> items = new HashMap<>();
			if (json.has("items") && json.get("items").isJsonObject()) {
				JsonObject itemsObj = json.getAsJsonObject("items");
				for (String key : itemsObj.keySet()) {
					JsonObject itemJson = itemsObj.getAsJsonObject(key);
					MenuItemDefinition itemDef = parseItem(key, itemJson);
					items.put(key, itemDef);
				}
			}

			CustomMenuDefinition def = new CustomMenuDefinition(id, title, size, openCmds, items);
			menus.put(id, def);
			for (String cmd : openCmds) {
				commandToMenuId.put(cmd, id);
			}
			LOGGER.info("[timesmp] Loaded custom menu: {} ({} items, commands: {})", id, items.size(), openCmds);
		} catch (Exception e) {
			LOGGER.error("[timesmp] Error parsing menu file: {}", file.getFileName(), e);
		}
	}

	private MenuItemDefinition parseItem(String key, JsonObject json) {
		String material = json.has("material") ? json.get("material").getAsString() : "STONE";
		int slot = json.has("slot") ? json.get("slot").getAsInt() : 0;
		List<Integer> slots = new ArrayList<>();
		if (json.has("slots") && json.get("slots").isJsonArray()) {
			for (JsonElement e : json.getAsJsonArray("slots")) slots.add(e.getAsInt());
		} else {
			slots.add(slot);
		}

		String displayName = json.has("display_name") ? json.get("display_name").getAsString() : null;
		List<String> lore = new ArrayList<>();
		if (json.has("lore") && json.get("lore").isJsonArray()) {
			for (JsonElement e : json.getAsJsonArray("lore")) lore.add(e.getAsString());
		}

		int customModelData = json.has("custom_model_data") ? json.get("custom_model_data").getAsInt() : (json.has("model_data") ? json.get("model_data").getAsInt() : 0);
		String customModelType = json.has("model_type") ? json.get("model_type").getAsString() : null;

		List<String> clickCmds = readCommands(json, "click_commands");
		List<String> leftClickCmds = readCommands(json, "left_click_commands");
		List<String> rightClickCmds = readCommands(json, "right_click_commands");

		return new MenuItemDefinition(key, material, slot, slots, displayName, lore, customModelData, customModelType, clickCmds, leftClickCmds, rightClickCmds);
	}

	private List<String> readCommands(JsonObject json, String key) {
		List<String> list = new ArrayList<>();
		if (json.has(key) && json.get(key).isJsonArray()) {
			for (JsonElement e : json.getAsJsonArray(key)) list.add(e.getAsString());
		}
		return list;
	}

	public boolean openMenu(ServerPlayer player, String menuIdOrCommand) {
		String id = commandToMenuId.getOrDefault(menuIdOrCommand.toLowerCase(), menuIdOrCommand.toLowerCase());
		CustomMenuDefinition def = menus.get(id);
		if (def == null) return false;

		int rows = def.getRows();
		int totalSlots = rows * 9;
		SimpleContainer container = new SimpleContainer(totalSlots);
		Map<Integer, MenuItemDefinition> slotToItem = new HashMap<>();

		for (MenuItemDefinition item : def.items().values()) {
			ItemStack stack = buildItemStack(item, player);
			for (int targetSlot : item.slots()) {
				if (targetSlot >= 0 && targetSlot < totalSlots) {
					container.setItem(targetSlot, stack.copy());
					slotToItem.put(targetSlot, item);
				}
			}
		}

		Component titleComponent = parseColoredText(replacePlaceholders(def.title(), player));

		VirtualChestMenu.open(
				player,
				titleComponent,
				rows,
				true, // Read-only interactive menu
				container,
				(slotIndex, button, input, p) -> {
					MenuItemDefinition clickedItem = slotToItem.get(slotIndex);
					if (clickedItem != null) {
						handleItemClick(clickedItem, button, p, def);
					}
				},
				null
		);
		return true;
	}

	private void handleItemClick(MenuItemDefinition item, int button, ServerPlayer player, CustomMenuDefinition currentMenu) {
		List<String> commandsToRun = new ArrayList<>();
		commandsToRun.addAll(item.clickCommands());
		if (button == 0) { // Left click
			commandsToRun.addAll(item.leftClickCommands());
		} else if (button == 1) { // Right click
			commandsToRun.addAll(item.rightClickCommands());
		}

		MinecraftServer server = player.level().getServer();
		if (server == null) server = ClaimMap.getServer();
		if (server == null) return;

		for (String rawCmd : commandsToRun) {
			String cmd = replacePlaceholders(rawCmd.trim(), player);
			if (cmd.startsWith("[close]")) {
				player.closeContainer();
			} else if (cmd.startsWith("[message]")) {
				String msg = cmd.substring("[message]".length()).trim();
				player.sendSystemMessage(parseColoredText(msg));
			} else if (cmd.startsWith("[opentextmenu]") || cmd.startsWith("[opengui]") || cmd.startsWith("[openmenu]")) {
				int prefixEnd = cmd.indexOf(' ');
				if (prefixEnd != -1) {
					String targetMenu = cmd.substring(prefixEnd + 1).trim();
					openMenu(player, targetMenu);
				}
			} else if (cmd.startsWith("[player]")) {
				String playerCmd = cmd.substring("[player]".length()).trim();
				if (playerCmd.startsWith("/")) playerCmd = playerCmd.substring(1);
				server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), playerCmd);
			} else if (cmd.startsWith("[console]")) {
				String consoleCmd = cmd.substring("[console]".length()).trim();
				if (consoleCmd.startsWith("/")) consoleCmd = consoleCmd.substring(1);
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), consoleCmd);
			} else {
				// Default is player command if without prefix or with /
				String execCmd = cmd.startsWith("/") ? cmd.substring(1) : cmd;
				server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), execCmd);
			}
		}
	}

	private ItemStack buildItemStack(MenuItemDefinition itemDef, ServerPlayer player) {
		String matName = itemDef.material().toLowerCase();
		if (!matName.contains(":")) matName = "minecraft:" + matName;
		Item item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(matName));
		if (item == null || item == Items.AIR) {
			item = Items.PAPER;
		}

		ItemStack stack = new ItemStack(item);

		// Name
		if (itemDef.displayName() != null) {
			String nameStr = replacePlaceholders(itemDef.displayName(), player);
			stack.set(DataComponents.CUSTOM_NAME, parseColoredText(nameStr));
		}

		// Lore
		if (itemDef.lore() != null && !itemDef.lore().isEmpty()) {
			List<Component> loreList = new ArrayList<>();
			for (String l : itemDef.lore()) {
				loreList.add(parseColoredText(replacePlaceholders(l, player)));
			}
			stack.set(DataComponents.LORE, new ItemLore(loreList));
		}

		// Custom Model Data
		int modelId = itemDef.customModelData();
		if (modelId <= 0 && itemDef.customModelType() != null) {
			modelId = GuiButtons.modelId(itemDef.customModelType());
		}
		if (modelId > 0) {
			stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of((float) modelId), List.of(), List.of(), List.of()));
		}

		return stack;
	}

	private String replacePlaceholders(String text, ServerPlayer player) {
		if (text == null) return "";
		String res = text.replace("%player_name%", player.getGameProfile().name())
				.replace("%player%", player.getGameProfile().name())
				.replace("%player_uuid%", player.getUUID().toString());

		ClaimMap claimMap = ClaimMap.instance();
		if (claimMap != null && claimMap.economy() != null) {
			long bal = claimMap.economy().balance(player.getUUID());
			res = res.replace("%balance%", String.format("%,d", bal))
					.replace("%vault_eco_balance%", String.format("%,d", bal));
		}
		return res;
	}

	private Component parseColoredText(String text) {
		if (text == null) return Component.empty();
		// Convert standard & color codes to Minecraft formatting
		String converted = text.replace('&', '§');
		return Component.literal(converted);
	}

	public Map<String, CustomMenuDefinition> getMenus() {
		return Collections.unmodifiableMap(menus);
	}

	public Map<String, String> getCommandToMenuId() {
		return Collections.unmodifiableMap(commandToMenuId);
	}
}

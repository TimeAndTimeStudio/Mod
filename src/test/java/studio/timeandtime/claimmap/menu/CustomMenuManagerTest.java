package studio.timeandtime.claimmap.menu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CustomMenuManagerTest {

	@Test
	void testCustomMenuParsingAndLoading(@TempDir Path tempDir) throws IOException {
		Path menusDir = tempDir.resolve("menus");
		Files.createDirectories(menusDir);

		String sampleMenuJson = """
				{
				  "menu_title": "&6&lTest Menu",
				  "open_command": ["testmenu", "tmenu"],
				  "size": 27,
				  "items": {
				    "info": {
				      "material": "paper",
				      "slot": 4,
				      "model_type": "cart",
				      "display_name": "&aInformation",
				      "lore": ["&7Line 1", "&eLine 2"],
				      "click_commands": ["[player] spawn"]
				    }
				  }
				}
				""";
		Files.writeString(menusDir.resolve("sample.json"), sampleMenuJson);

		CustomMenuManager manager = new CustomMenuManager(tempDir);
		manager.load();

		assertEquals(1, manager.getMenus().size());
		CustomMenuDefinition def = manager.getMenus().get("sample");
		assertNotNull(def);
		assertEquals(3, def.getRows());
		assertEquals("&6&lTest Menu", def.title());
		assertTrue(def.openCommands().contains("testmenu"));
		assertTrue(def.openCommands().contains("tmenu"));

		assertEquals("sample", manager.getCommandToMenuId().get("testmenu"));
		assertEquals("sample", manager.getCommandToMenuId().get("tmenu"));

		MenuItemDefinition item = def.items().get("info");
		assertNotNull(item);
		assertEquals("paper", item.material());
		assertEquals(4, item.slot());
		assertEquals("cart", item.customModelType());
		assertEquals("&aInformation", item.displayName());
		assertEquals(2, item.lore().size());
		assertEquals(1, item.clickCommands().size());
		assertEquals("[player] spawn", item.clickCommands().get(0));
	}
}

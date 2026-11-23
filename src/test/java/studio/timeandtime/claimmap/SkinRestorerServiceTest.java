package studio.timeandtime.claimmap;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.timeandtime.claimmap.skin.SkinRestorerService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SkinRestorerServiceTest {

	@Test
	void nullProfileReturnsNull() {
		assertNull(SkinRestorerService.applySkin(null));
	}

	@Test
	void customSkinTargetMappingWorks() {
		String player = "LocalPlayer123";
		assertEquals(player, SkinRestorerService.getSkinTarget(player));

		SkinRestorerService.setSkin(player, "Notch");
		assertEquals("Notch", SkinRestorerService.getSkinTarget(player));
	}

	@Test
	void applySkinKeepsExistingProfileData() {
		UUID uuid = UUID.randomUUID();
		GameProfile profile = new GameProfile(uuid, "TestUser");
		GameProfile modified = SkinRestorerService.applySkin(profile);

		assertNotNull(modified);
		assertEquals(uuid, modified.id());
		assertEquals("TestUser", modified.name());
	}

	@Test
	void localSkinFolderLoadsProperly(@TempDir Path tempDir) throws IOException {
		Path skinsDir = SkinRestorerService.getSkinsDir();
		assertNotNull(skinsDir);

		// Test build custom URL property
		Property prop = SkinRestorerService.buildCustomUrlProperty("http://example.com/skin.png", "CustomUser");
		assertNotNull(prop);
		assertEquals("textures", prop.name());
		assertNotNull(prop.value());
	}
}

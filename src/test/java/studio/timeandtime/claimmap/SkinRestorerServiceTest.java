package studio.timeandtime.claimmap;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.timeandtime.claimmap.skin.SkinRestorerService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

	private static String decodeTextures(Property property) {
		return new String(java.util.Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8);
	}

	@Test
	void alexModelIsWrittenIntoTexturesMetadata() throws IOException {
		String name = "ModelTest" + UUID.randomUUID().toString().substring(0, 8);
		Path dir = SkinRestorerService.getSkinsDir();
		Path png = dir.resolve(name.toLowerCase() + ".png");
		Path model = dir.resolve(name.toLowerCase() + ".model");
		try {
			Files.createDirectories(dir);
			Files.write(png, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0});

			// Default and explicit Steve: no slim metadata
			assertFalse(SkinRestorerService.isSlim(name));
			SkinRestorerService.setSlim(name, false);
			assertFalse(decodeTextures(SkinRestorerService.fetchSkinProperty(name)).contains("\"model\""));

			// Alex: slim metadata is present
			SkinRestorerService.setSlim(name, true);
			assertTrue(SkinRestorerService.isSlim(name));
			assertTrue(decodeTextures(SkinRestorerService.fetchSkinProperty(name)).contains("\"model\":\"slim\""));
		} finally {
			Files.deleteIfExists(png);
			Files.deleteIfExists(model);
		}
	}
}

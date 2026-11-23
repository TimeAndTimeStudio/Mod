package studio.timeandtime.claimmap;

import com.mojang.authlib.GameProfile;
import org.junit.jupiter.api.Test;
import studio.timeandtime.claimmap.skin.SkinRestorerService;

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
}

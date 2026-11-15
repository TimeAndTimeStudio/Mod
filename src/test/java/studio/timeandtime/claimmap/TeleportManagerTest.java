package studio.timeandtime.claimmap;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TeleportManagerTest {

	@Test
	void requestCreationAndExpiration() {
		TeleportManager tm = new TeleportManager();
		UUID sender = UUID.randomUUID();
		UUID target = UUID.randomUUID();

		TeleportManager.Request req = new TeleportManager.Request(sender, "Alice", target, "Bob", 1000L);
		assertFalse(req.isExpired(1000L + TeleportManager.TIMEOUT_MS));
		assertTrue(req.isExpired(1000L + TeleportManager.TIMEOUT_MS + 1L));
	}
}

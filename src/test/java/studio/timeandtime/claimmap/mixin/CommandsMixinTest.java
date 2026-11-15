package studio.timeandtime.claimmap.mixin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandsMixinTest {

	@Test
	void testAllowedWhisperCommands() {
		assertTrue(CommandsMixin.isAllowedCommand("msg", null));
		assertTrue(CommandsMixin.isAllowedCommand("tell", null));
		assertTrue(CommandsMixin.isAllowedCommand("w", null));
		assertTrue(CommandsMixin.isAllowedCommand("MSG", null));
		assertTrue(CommandsMixin.isAllowedCommand("Tell", null));
	}

	@Test
	void testAllowedModCommands() {
		assertTrue(CommandsMixin.isAllowedCommand("tp", null));
		assertTrue(CommandsMixin.isAllowedCommand("tpa", null));
		assertTrue(CommandsMixin.isAllowedCommand("tpaccept", null));
		assertTrue(CommandsMixin.isAllowedCommand("tpdeny", null));
		assertTrue(CommandsMixin.isAllowedCommand("tpacancel", null));
		assertTrue(CommandsMixin.isAllowedCommand("pass", null));
		assertTrue(CommandsMixin.isAllowedCommand("bal", null));
		assertTrue(CommandsMixin.isAllowedCommand("pay", null));
		assertTrue(CommandsMixin.isAllowedCommand("sell", null));
		assertTrue(CommandsMixin.isAllowedCommand("buy", null));
		assertTrue(CommandsMixin.isAllowedCommand("mail", null));
		assertTrue(CommandsMixin.isAllowedCommand("ah", null));
		assertTrue(CommandsMixin.isAllowedCommand("claim", null));
		assertTrue(CommandsMixin.isAllowedCommand("claimmap", null));
		assertTrue(CommandsMixin.isAllowedCommand("spawn", null));
		assertTrue(CommandsMixin.isAllowedCommand("main", null));
		assertTrue(CommandsMixin.isAllowedCommand("world", null));
	}

	@Test
	void testDisallowedVanillaAndAdminCommands() {
		assertFalse(CommandsMixin.isAllowedCommand("help", null));
		assertFalse(CommandsMixin.isAllowedCommand("me", null));
		assertFalse(CommandsMixin.isAllowedCommand("gamemode", null));
		assertFalse(CommandsMixin.isAllowedCommand("op", null));
		assertFalse(CommandsMixin.isAllowedCommand("deop", null));
		assertFalse(CommandsMixin.isAllowedCommand("team", null));
		assertFalse(CommandsMixin.isAllowedCommand("trigger", null));
		assertFalse(CommandsMixin.isAllowedCommand("eco", null)); // eco is admin only
		assertFalse(CommandsMixin.isAllowedCommand("worlds", null)); // worlds admin
		assertFalse(CommandsMixin.isAllowedCommand("", null));
		assertFalse(CommandsMixin.isAllowedCommand(null, null));
	}
}

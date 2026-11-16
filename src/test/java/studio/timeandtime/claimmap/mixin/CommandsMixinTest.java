package studio.timeandtime.claimmap.mixin;

import org.junit.jupiter.api.Test;
import studio.timeandtime.claimmap.CommandFilter;

import static org.junit.jupiter.api.Assertions.*;

class CommandsMixinTest {

	@Test
	void testAllowedWhisperCommands() {
		assertTrue(CommandFilter.isAllowedCommand("msg", null));
		assertTrue(CommandFilter.isAllowedCommand("tell", null));
		assertTrue(CommandFilter.isAllowedCommand("w", null));
		assertTrue(CommandFilter.isAllowedCommand("MSG", null));
		assertTrue(CommandFilter.isAllowedCommand("Tell", null));
	}

	@Test
	void testAllowedModCommands() {
		assertTrue(CommandFilter.isAllowedCommand("tp", null));
		assertTrue(CommandFilter.isAllowedCommand("tpa", null));
		assertTrue(CommandFilter.isAllowedCommand("tpaccept", null));
		assertTrue(CommandFilter.isAllowedCommand("tpdeny", null));
		assertTrue(CommandFilter.isAllowedCommand("tpacancel", null));
		assertTrue(CommandFilter.isAllowedCommand("pass", null));
		assertTrue(CommandFilter.isAllowedCommand("bal", null));
		assertTrue(CommandFilter.isAllowedCommand("pay", null));
		assertTrue(CommandFilter.isAllowedCommand("sell", null));
		assertTrue(CommandFilter.isAllowedCommand("buy", null));
		assertTrue(CommandFilter.isAllowedCommand("mail", null));
		assertTrue(CommandFilter.isAllowedCommand("ah", null));
		assertTrue(CommandFilter.isAllowedCommand("claim", null));
		assertTrue(CommandFilter.isAllowedCommand("claimmap", null));
		assertTrue(CommandFilter.isAllowedCommand("timesmp", null));
		assertTrue(CommandFilter.isAllowedCommand("main", null));
		assertTrue(CommandFilter.isAllowedCommand("world", null));
	}

	@Test
	void testDisallowedVanillaAndAdminCommands() {
		assertFalse(CommandFilter.isAllowedCommand("spawn", null));
		assertFalse(CommandFilter.isAllowedCommand("help", null));
		assertFalse(CommandFilter.isAllowedCommand("me", null));
		assertFalse(CommandFilter.isAllowedCommand("gamemode", null));
		assertFalse(CommandFilter.isAllowedCommand("op", null));
		assertFalse(CommandFilter.isAllowedCommand("deop", null));
		assertFalse(CommandFilter.isAllowedCommand("team", null));
		assertFalse(CommandFilter.isAllowedCommand("trigger", null));
		assertFalse(CommandFilter.isAllowedCommand("eco", null)); // eco is admin only
		assertFalse(CommandFilter.isAllowedCommand("worlds", null)); // worlds admin
		assertFalse(CommandFilter.isAllowedCommand("", null));
		assertFalse(CommandFilter.isAllowedCommand(null, null));
	}
}

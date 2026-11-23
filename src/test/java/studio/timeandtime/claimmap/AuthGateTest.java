package studio.timeandtime.claimmap;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.dialog.Dialog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The login state machine, without needing a Minecraft client. */
class AuthGateTest {
	@TempDir
	Path tmp;

	private AuthGate gate;
	private final UUID uuid = UUID.fromString("11111111-1111-4111-8111-111111111111");

	@BeforeEach
	void setUp() {
		ClaimMapConfig cfg = new ClaimMapConfig(tmp.resolve("claimmap.properties"));
		cfg.startingBalance = 0;
		AccountManager accounts = new AccountManager(cfg);
		LinkManager links = new LinkManager(cfg);
		EconomyManager economy = new EconomyManager(tmp, cfg, () -> 1L);
		economy.load();
		gate = new AuthGate(cfg, accounts, links, economy);
	}

	@Test
	void existingAccountStartsAtLoginOtherwiseRegister() {
		assertEquals(AuthGate.Phase.NEED_LOGIN, gate.begin(uuid, true, 1000L, 0, 64, 0).phase);
		gate.clear(uuid);
		assertEquals(AuthGate.Phase.NEED_REGISTER, gate.begin(uuid, false, 1000L, 0, 64, 0).phase);
		assertFalse(gate.isAuthed(uuid));
	}

	@Test
	void becomesAuthedOnlyAfterMarking() {
		gate.begin(uuid, true, 1000L, 0, 64, 0);
		gate.markAuthed(uuid);
		assertTrue(gate.isAuthed(uuid));
		assertEquals(AuthGate.Phase.AUTHED, gate.phase(uuid));
	}

	@Test
	void threeFailuresRequireAKick() {
		gate.begin(uuid, true, 1000L, 0, 64, 0);
		assertEquals(1, gate.fail(uuid));
		assertFalse(gate.shouldKickForFails(uuid));
		gate.fail(uuid);
		assertFalse(gate.shouldKickForFails(uuid));
		assertEquals(3, gate.fail(uuid));
		assertTrue(gate.shouldKickForFails(uuid));
	}

	@Test
	void sixtySecondsWithoutAPasswordTimesOut() {
		gate.begin(uuid, true, 1000L, 0, 64, 0);
		assertFalse(gate.timedOut(uuid, 1000L + 59_999L));
		assertTrue(gate.timedOut(uuid, 1000L + 60_000L));
		gate.markAuthed(uuid);
		assertFalse(gate.timedOut(uuid, 1000L + 3_600_000L), "authed players never time out");
	}

	@Test
	void clearingRemovesTheState() {
		gate.begin(uuid, true, 1000L, 0, 64, 0);
		gate.clear(uuid);
		assertNull(gate.phase(uuid));
		assertFalse(gate.isAuthed(uuid));
	}

	@Test
	void bothDialogsEncodeWithTheContextFreeCodec() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		for (Dialog dialog : new Dialog[]{AuthGate.loginDialog(null), AuthGate.registerDialog(null),
				AuthGate.loginDialog("รหัสผ่านไม่ถูกต้อง"), AuthGate.skinDialog(null),
				AuthGate.skinDialog("URL ไม่ถูกต้อง")}) {
			FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
			Dialog.CONTEXT_FREE_STREAM_CODEC.encode(buffer, dialog);
			assertTrue(buffer.readableBytes() > 0, "dialog must serialize for the configuration phase");
		}
	}

	@Test
	void onlyStringPayloadFieldsAreAccepted() {
		CompoundTag tag = new CompoundTag();
		tag.put("password", StringTag.valueOf("secret1"));
		tag.put("confirm", StringTag.valueOf("secret1"));
		tag.put("amount", IntTag.valueOf(10));
		assertEquals("secret1", AuthGate.string(tag, "password"));
		assertEquals("secret1", AuthGate.string(tag, "confirm"));
		assertNull(AuthGate.string(tag, "missing"));
		assertNull(AuthGate.string(tag, "amount"), "non-string values are ignored");
	}

	@Test
	void passwordRules() {
		assertEquals("รหัสผ่านต้องยาว 6-64 ตัวอักษร", AuthGate.passwordProblem("abc", "abc", "Alice"));
		assertEquals("รหัสผ่านต้องยาว 6-64 ตัวอักษร", AuthGate.passwordProblem(null, null, "Alice"));
		assertEquals("รหัสยืนยันไม่ตรงกัน", AuthGate.passwordProblem("secret1", "secret2", "Alice"));
		assertEquals("รหัสผ่านต้องไม่ซ้ำกับชื่อผู้เล่น", AuthGate.passwordProblem("ALICE123", "ALICE123", "alice123"));
		assertNull(AuthGate.passwordProblem("secret1", "secret1", "Alice"));
	}
}

package studio.timeandtime.claimmap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailboxManagerTest {
	@TempDir
	Path tmp;

	private final UUID alice = UUID.fromString("11111111-1111-4111-8111-111111111111");
	private long clock = 1000L;
	private MailboxManager mailbox;

	@BeforeEach
	void setUp() {
		mailbox = new MailboxManager(tmp.resolve("mailbox.json"), () -> clock, 3);
		mailbox.load();
	}

	private MailboxManager.MailEntry entry(String id, int count) {
		return new MailboxManager.MailEntry(id, "{\"id\":\"minecraft:diamond\",\"count\":" + count + "}",
				"minecraft:diamond", count, "เพชร", "shop", clock);
	}

	@Test
	void duplicateIdsAreIgnored() {
		assertTrue(mailbox.add(alice, entry("mb_1", 64)));
		assertFalse(mailbox.add(alice, entry("mb_1", 64)), "retrying the same id must not add a second entry");
		assertEquals(1, mailbox.count(alice));
	}

	@Test
	void capacityIsEnforced() {
		assertTrue(mailbox.hasCapacity(alice, 3));
		assertTrue(mailbox.add(alice, entry("mb_1", 1)));
		assertTrue(mailbox.add(alice, entry("mb_2", 1)));
		assertTrue(mailbox.hasCapacity(alice, 1));
		assertFalse(mailbox.hasCapacity(alice, 2));
		assertTrue(mailbox.add(alice, entry("mb_3", 1)));
		assertFalse(mailbox.add(alice, entry("mb_4", 1)));
		assertEquals(3, mailbox.count(alice));
	}

	@Test
	void replaceUpdatesOrRemoves() {
		mailbox.add(alice, entry("mb_1", 64));
		mailbox.replace(alice, "mb_1", entry("mb_1", 10));
		assertEquals(10, mailbox.list(alice).get(0).count());
		mailbox.replace(alice, "mb_1", null);
		assertEquals(0, mailbox.count(alice));
	}

	@Test
	void survivesARestart() {
		mailbox.add(alice, entry("mb_1", 64));

		MailboxManager reloaded = new MailboxManager(tmp.resolve("mailbox.json"), () -> clock, 3);
		reloaded.load();

		assertFalse(reloaded.isDisabled());
		assertEquals(1, reloaded.count(alice));
		MailboxManager.MailEntry e = reloaded.list(alice).get(0);
		assertEquals("mb_1", e.id());
		assertEquals(64, e.count());
		assertEquals("เพชร", e.label());
	}

	@Test
	void damagedFileDisablesTheMailboxWithoutRecreatingIt() throws Exception {
		Files.writeString(tmp.resolve("mailbox.json"), "{ this is not json", StandardCharsets.UTF_8);
		MailboxManager reloaded = new MailboxManager(tmp.resolve("mailbox.json"), () -> clock, 3);
		reloaded.load();

		assertTrue(reloaded.isDisabled());
		assertFalse(reloaded.add(alice, entry("mb_1", 1)));
		assertFalse(Files.exists(tmp.resolve("mailbox.json")));
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmp, "mailbox.json.corrupt-*")) {
			assertTrue(stream.iterator().hasNext());
		}
	}

	@Test
	void emptyFileMeansAFreshMailbox() throws Exception {
		assertEquals(0, mailbox.count(alice));
		assertNull(mailbox.list(alice).isEmpty() ? null : "unexpected");
		assertNotNull(mailbox);
	}
}

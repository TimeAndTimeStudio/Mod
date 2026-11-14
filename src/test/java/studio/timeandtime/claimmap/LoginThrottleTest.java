package studio.timeandtime.claimmap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginThrottleTest {

	@Test
	void locksForOneMinuteAfterFiveFailures() {
		LoginThrottle t = new LoginThrottle();
		String key = LoginThrottle.key("10.0.0.1", "Alice");
		long now = 1_000_000L;

		for (int i = 0; i < LoginThrottle.MAX_FAILS - 1; i++) {
			t.fail(key, now);
			assertEquals(0L, t.retryAfterMs(key, now), "must not lock before the 5th failure");
		}
		t.fail(key, now);
		assertEquals(LoginThrottle.LOCK_MS, t.retryAfterMs(key, now));

		// Being locked does not extend the lock no matter how many more attempts.
		t.fail(key, now + 1_000L);
		assertEquals(LoginThrottle.LOCK_MS - 1_000L, t.retryAfterMs(key, now + 1_000L));
	}

	@Test
	void unlockAfterTheLockExpires() {
		LoginThrottle t = new LoginThrottle();
		String key = LoginThrottle.key("10.0.0.1", "Alice");
		long now = 5_000L;
		for (int i = 0; i < LoginThrottle.MAX_FAILS; i++) t.fail(key, now);

		assertTrue(t.retryAfterMs(key, now + LoginThrottle.LOCK_MS) == 0L);
		// After expiry the counter starts from scratch: 4 more failures do not lock.
		long after = now + LoginThrottle.LOCK_MS;
		for (int i = 0; i < LoginThrottle.MAX_FAILS - 1; i++) {
			t.fail(key, after);
			assertEquals(0L, t.retryAfterMs(key, after));
		}
	}

	@Test
	void successClearsTheCounter() {
		LoginThrottle t = new LoginThrottle();
		String key = LoginThrottle.key("10.0.0.1", "Alice");
		long now = 1L;
		for (int i = 0; i < LoginThrottle.MAX_FAILS; i++) t.fail(key, now);
		t.success(key);
		assertEquals(0L, t.retryAfterMs(key, now));

		for (int i = 0; i < LoginThrottle.MAX_FAILS - 1; i++) t.fail(key, now);
		assertEquals(0L, t.retryAfterMs(key, now), "counter must restart after success");
	}

	@Test
	void keyIgnoresCaseAndTrims() {
		assertEquals(LoginThrottle.key("1.2.3.4", "Alice "), LoginThrottle.key("1.2.3.4", "alice"));
		assertNotEquals(LoginThrottle.key("1.2.3.4", "Alice"), LoginThrottle.key("1.2.3.5", "Alice"));
	}

	@Test
	void separateAccountsAreThrottledIndependently() {
		LoginThrottle t = new LoginThrottle();
		String alice = LoginThrottle.key("10.0.0.1", "Alice");
		String bob = LoginThrottle.key("10.0.0.1", "Bob");
		long now = 10L;
		for (int i = 0; i < LoginThrottle.MAX_FAILS; i++) t.fail(alice, now);
		assertTrue(t.retryAfterMs(alice, now) > 0);
		assertEquals(0L, t.retryAfterMs(bob, now));
	}
}

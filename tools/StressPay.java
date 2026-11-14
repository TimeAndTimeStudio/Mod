import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fires concurrent POST /api/pay requests and checks that the total money of
 * both accounts is unchanged and that no balance went negative.
 *
 * <pre>java tools/StressPay.java [baseUrl] [user1] [pass1] [user2] [pass2] [count]</pre>
 *
 * Defaults: http://127.0.0.1:8085 Alice secret1 Bob secret2 100
 * Requires both accounts to exist (set a password in game with /pass).
 */
public final class StressPay {
	private static final Pattern BALANCE = Pattern.compile("\"balance\"\\s*:\\s*(-?\\d+)");
	private static final Pattern SESSION = Pattern.compile("\"session\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern ERROR = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]+)\"");

	public static void main(String[] args) throws Exception {
		String base = args.length > 0 ? args[0] : "http://127.0.0.1:8085";
		String u1 = args.length > 1 ? args[1] : "Alice";
		String p1 = args.length > 2 ? args[2] : "secret1";
		String u2 = args.length > 3 ? args[3] : "Bob";
		String p2 = args.length > 4 ? args[4] : "secret2";
		int count = args.length > 5 ? Integer.parseInt(args[5]) : 100;
		long amount = 1;

		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		String token1 = login(http, base, u1, p1);
		String token2 = login(http, base, u2, p2);
		long before1 = balance(http, base, token1);
		long before2 = balance(http, base, token2);

		ExecutorService pool = Executors.newFixedThreadPool(16);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger ok = new AtomicInteger();
		AtomicInteger failed = new AtomicInteger();
		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			final int n = i;
			futures.add(pool.submit(() -> {
				try {
					start.await();
					HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/pay"))
							.timeout(Duration.ofSeconds(20))
							.header("Content-Type", "application/json")
							.header("Authorization", "Bearer " + token1)
							.header("X-Request-Id", "stress-" + n)
							.POST(HttpRequest.BodyPublishers.ofString(
									"{\"to\":\"" + u2 + "\",\"amount\":" + amount + "}"))
							.build();
					HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
					if (res.statusCode() == 200) ok.incrementAndGet();
					else {
						failed.incrementAndGet();
						Matcher m = ERROR.matcher(res.body());
						System.out.println("  request " + n + " -> " + res.statusCode()
								+ (m.find() ? " " + m.group(1) : ""));
					}
				} catch (Exception e) {
					failed.incrementAndGet();
					System.out.println("  request " + n + " threw " + e);
				}
			}));
		}
		start.countDown();
		pool.shutdown();
		if (!pool.awaitTermination(120, TimeUnit.SECONDS)) {
			System.out.println("FAIL: requests did not finish in time");
			System.exit(1);
		}
		for (Future<?> f : futures) f.get();

		long after1 = balance(http, base, token1);
		long after2 = balance(http, base, token2);

		System.out.println("requests: ok=" + ok.get() + " failed=" + failed.get());
		System.out.println(u1 + ": " + before1 + " -> " + after1);
		System.out.println(u2 + ": " + before2 + " -> " + after2);

		boolean pass = true;
		long delta1 = after1 - before1;
		long delta2 = after2 - before2;
		if (after1 < 0 || after2 < 0) {
			System.out.println("FAIL: negative balance");
			pass = false;
		}
		if (delta1 + delta2 != 0) {
			System.out.println("FAIL: money was created or destroyed (delta " + (delta1 + delta2) + ")");
			pass = false;
		}
		if (delta2 != (long) ok.get() * amount) {
			System.out.println("FAIL: receiver delta " + delta2 + " != successful payments " + ok.get() + " x " + amount);
			pass = false;
		}
		System.out.println(pass ? "PASS: concurrent payments kept the total constant" : "FAIL");
		if (!pass) System.exit(1);
	}

	private static String login(HttpClient http, String base, String user, String pass) throws Exception {
		String body = "{\"name\":\"" + user + "\",\"password\":\"" + pass + "\"}";
		HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/login"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200) {
			throw new IllegalStateException("login failed for " + user + ": " + res.statusCode() + " " + res.body());
		}
		Matcher m = SESSION.matcher(res.body());
		if (!m.find()) throw new IllegalStateException("no session token in login response");
		return m.group(1);
	}

	private static long balance(HttpClient http, String base, String token) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/wallet"))
				.header("Authorization", "Bearer " + token)
				.GET()
				.build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200) {
			throw new IllegalStateException("wallet failed: " + res.statusCode() + " " + res.body());
		}
		Matcher m = BALANCE.matcher(res.body());
		if (!m.find()) throw new IllegalStateException("no balance in wallet response");
		return Long.parseLong(m.group(1));
	}

	private StressPay() {
	}
}

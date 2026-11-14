import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends malformed API requests and checks the status codes from PHASES.md E.
 * No dependencies; run with the JDK alone:
 *
 * <pre>java tools/ApiFuzz.java http://127.0.0.1:8085 &lt;player session token&gt;</pre>
 *
 * The token is optional: without it only the unauthenticated checks run.
 * Any 500 is a failure. Exit code 1 when something did not match.
 */
public final class ApiFuzz {

	record Case(String name, String method, String path, String body, String token, int expect) {
	}

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private static final List<String> failures = new ArrayList<>();

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("usage: java tools/ApiFuzz.java <base-url> [session-token]");
			System.exit(2);
		}
		String base = args[0].replaceAll("/+$", "");
		String token = args.length > 1 ? args[1] : null;
		String big = "{\"to\":\"Alice\",\"amount\":1,\"pad\":\"" + "a".repeat(20_000) + "\"}";

		List<Case> cases = new ArrayList<>();
		cases.add(new Case("no session /api/cells", "GET", "/api/cells", null, null, 401));
		cases.add(new Case("bad token /api/wallet", "GET", "/api/wallet", null, "not-a-real-token", 401));
		cases.add(new Case("unknown path", "GET", "/api/does-not-exist", null, null, 404));
		cases.add(new Case("wrong method", "GET", "/api/pay", null, null, 405));
		cases.add(new Case("broken json", "POST", "/api/pay", "{oops", token, token == null ? 401 : 400));
		cases.add(new Case("oversized body", "POST", "/api/pay", big, token, token == null ? 401 : 413));

		if (token != null) {
			cases.add(new Case("pay amount 0", "POST", "/api/pay", "{\"to\":\"Alice\",\"amount\":0}", token, 400));
			cases.add(new Case("pay amount negative", "POST", "/api/pay", "{\"to\":\"Alice\",\"amount\":-5}", token, 400));
			cases.add(new Case("pay amount decimal", "POST", "/api/pay", "{\"to\":\"Alice\",\"amount\":1.5}", token, 400));
			cases.add(new Case("pay amount string", "POST", "/api/pay", "{\"to\":\"Alice\",\"amount\":\"10\"}", token, 400));
			cases.add(new Case("pay amount exponent", "POST", "/api/pay", "{\"to\":\"Alice\",\"amount\":1e3}", token, 400));
			cases.add(new Case("buy qty 0", "POST", "/api/shop/buy", "{\"item\":\"diamond\",\"qty\":0}", token, 400));
			cases.add(new Case("buy qty decimal", "POST", "/api/shop/buy", "{\"item\":\"diamond\",\"qty\":1.5}", token, 400));
			cases.add(new Case("buy bad item id", "POST", "/api/shop/buy", "{\"item\":\"../nope\",\"qty\":1}", token, 400));
			cases.add(new Case("bid missing auction", "POST", "/api/auctions/nope/bid", "{\"amount\":10}", token, 404));
			cases.add(new Case("admin as player", "GET", "/api/admin/overview", null, token, 403));
		}

		for (Case c : cases) {
			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + c.path()))
					.timeout(Duration.ofSeconds(10));
			if (c.token() != null) b.header("Authorization", "Bearer " + c.token());
			if (c.body() != null) {
				b.header("Content-Type", "application/json");
				b.method(c.method(), HttpRequest.BodyPublishers.ofString(c.body(), StandardCharsets.UTF_8));
			} else {
				b.method(c.method(), HttpRequest.BodyPublishers.noBody());
			}
			int status;
			String text;
			try {
				HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				status = r.statusCode();
				text = r.body();
			} catch (Exception e) {
				status = -1;
				text = e.toString();
			}
			boolean ok = status == c.expect() && status != 500;
			System.out.printf("%s %-28s expected %d got %d  %s%n",
					ok ? "PASS" : "FAIL", c.name(), c.expect(), status,
					text.length() > 90 ? text.substring(0, 90) + "…" : text);
			if (!ok) failures.add(c.name());
		}

		System.out.println(failures.isEmpty()
				? "ApiFuzz: PASS (" + cases.size() + " cases)"
				: "ApiFuzz: FAIL " + failures);
		if (!failures.isEmpty()) System.exit(1);
	}
}

package studio.timeandtime.launcher;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Time SMP server launcher: the jar version of run.sh.
 *
 * <pre>
 *   java -jar timesmp-run.jar [server-dir]
 *   RAM=4G java -jar timesmp-run.jar [server-dir]
 * </pre>
 *
 * It starts "java &lt;jvm flags&gt; -jar server.jar nogui" inside the server
 * directory (the folder that holds server.jar, created by setup.sh). The JVM
 * flags are the ones the launcher was started with (e.g. -Xmx2048M,
 * -Dterminal.jline=false); RAM=... overrides the heap size.
 *
 * Server directory, first match wins: the first argument, the SERVER_DIR
 * environment variable, the folder of this jar or the current folder when
 * they contain server.jar, then $HOME/mcserver (the setup.sh default).
 */
public final class RunMain {
	private static final Pattern RAM_PATTERN = Pattern.compile("^([0-9]+)([GgMm]?)$");

	private RunMain() {
	}

	public static void main(String[] args) throws Exception {
		Path dir = resolveServerDir(args);
		File serverJar = dir.resolve("server.jar").toFile();
		if (!serverJar.isFile()) {
			System.err.println("[run] server.jar not found in " + dir
					+ " (run setup.sh first, or pass the server folder: java -jar timesmp-run.jar <dir>)");
			System.exit(1);
		}

		// JVM flags the launcher itself was started with (-Xmx, -D..., -XX:...) are
		// forwarded to the server JVM, so hosting panels that start
		// "java <flags> -jar timesmp-run.jar" configure the real server.
		List<String> jvmArgs = new ArrayList<>(ManagementFactory.getRuntimeMXBean().getInputArguments());

		// RAM=... (env) overrides the heap; otherwise keep the forwarded -Xmx,
		// and fall back to 2G only when neither is given.
		String envRam = System.getenv("RAM");
		boolean hasXmx = jvmArgs.stream().anyMatch(a -> a.startsWith("-Xmx"));
		if (envRam != null && !envRam.isBlank() || !hasXmx) {
			String ram = normalizeRam(envRam);
			if (ram == null) {
				System.err.println("[run] invalid RAM '" + envRam + "' (use e.g. 4 or 4G or 4096M)");
				System.exit(1);
			}
			jvmArgs.removeIf(a -> a.startsWith("-Xmx") || a.startsWith("-Xms"));
			jvmArgs.add("-Xms" + ram);
			jvmArgs.add("-Xmx" + ram);
		}

		String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
		List<String> cmd = new ArrayList<>();
		cmd.add(javaBin);
		cmd.addAll(jvmArgs);
		cmd.add("-jar");
		cmd.add("server.jar");
		cmd.add("nogui");

		System.out.println("[run] " + String.join(" ", cmd) + "  (in " + dir + ")");
		ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO();
		Process process = pb.start();
		// Ctrl+C / kill on the launcher also stops the server so it saves the world.
		Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
		System.exit(process.waitFor());
	}

	static Path resolveServerDir(String[] args) throws Exception {
		if (args.length > 0 && !args[0].isBlank()) {
			return Paths.get(args[0]).toAbsolutePath().normalize();
		}
		String env = System.getenv("SERVER_DIR");
		if (env != null && !env.isBlank()) {
			return Paths.get(env).toAbsolutePath().normalize();
		}
		Path jarDir = Paths.get(RunMain.class.getProtectionDomain().getCodeSource().getLocation().toURI())
				.toAbsolutePath().getParent();
		if (jarDir != null && jarDir.resolve("server.jar").toFile().isFile()) {
			return jarDir;
		}
		Path cwd = Paths.get("").toAbsolutePath();
		if (cwd.resolve("server.jar").toFile().isFile()) {
			return cwd;
		}
		return Paths.get(System.getProperty("user.home"), "mcserver");
	}

	/** "4" -> "4G", "4g" -> "4G", "4096m" -> "4096M", unset -> "2G"; null when invalid. */
	static String normalizeRam(String raw) {
		if (raw == null || raw.isBlank()) {
			return "2G";
		}
		var m = RAM_PATTERN.matcher(raw.trim());
		if (!m.matches() || Long.parseLong(m.group(1)) < 1) {
			return null;
		}
		String unit = m.group(2).isEmpty() ? "G" : m.group(2).toUpperCase();
		return m.group(1) + unit;
	}
}

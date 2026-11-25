package studio.timeandtime.setup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Time SMP server setup, 100% Java (port of setup.sh, no bash/curl/jq/unzip needed).
 *
 * Usage: java -jar timesmp-setup.jar [server-dir]
 *
 * Environment variables (same as setup.sh):
 *   RAM=4G  FORCE=1  MC_VERSION  REPO  MOD_TAG  MOD_URL  WORLDGEN_RELEASE  LOBBY_URL  FIND_URL
 *   SERVER_DIR (install dir; defaults to ./msserver on Pterodactyl)
 */
public final class SetupMain {

    static final class SetupException extends RuntimeException {
        SetupException(String msg) { super(msg); }
    }

    static void log(String s) { System.out.println("[setup] " + s); }

    static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (SetupException e) {
            System.err.println("[setup] ERROR: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("[setup] ERROR: " + e);
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        String dirArg = null;
        for (String a : args) {
            if (a.equals("-h") || a.equals("--help")) {
                usage();
                return;
            }
            if (dirArg == null) dirArg = a;
        }

        String mcVersion = env("MC_VERSION", "26.3");
        String repo = env("REPO", "TimeAndTimeStudio/Mod");
        String modTag = env("MOD_TAG", "mod_26.3");
        boolean is263 = mcVersion.equals("26.3");
        String modAsset = is263 ? "timesmp.jar" : "timesmp-mc" + mcVersion + ".jar";
        String modUrl = env("MOD_URL", "https://github.com/" + repo + "/releases/download/" + modTag + "/" + modAsset);
        String worldgenRelease = env("WORLDGEN_RELEASE", "mod_26.3");
        String lobbyUrl = env("LOBBY_URL", "https://github.com/" + repo + "/releases/download/maplobby/world-lobby-2026-11-17.zip");
        String findUrl = env("FIND_URL", "https://github.com/" + repo + "/releases/download/findmap/find.zip");
        String base = "https://github.com/" + repo + "/releases/download/" + worldgenRelease + "/";

        // Server dir: argument > SERVER_DIR env > ./msserver on Pterodactyl > ~/mcserver
        boolean pterodactyl = System.getenv("P_SERVER_UUID") != null;
        Path serverDir;
        if (dirArg != null) serverDir = Paths.get(dirArg);
        else if (System.getenv("SERVER_DIR") != null && !System.getenv("SERVER_DIR").isEmpty()) serverDir = Paths.get(System.getenv("SERVER_DIR"));
        else if (pterodactyl) serverDir = Paths.get("msserver");
        else serverDir = Paths.get(System.getProperty("user.home"), "mcserver");
        serverDir = serverDir.toAbsolutePath().normalize();

        // 0) Java check: only a warning (the installer itself just downloads files;
        //    the mod is built for Java 25+, so the host decides the runtime).
        int javaMajor = Runtime.version().feature();
        if (javaMajor < 25) {
            log("WARNING: Java " + javaMajor + " found, the server needs Java 25+ to run");
        } else {
            log("Java " + javaMajor + ": OK");
        }

        // 0) RAM
        String ram = env("RAM", null);
        if (ram == null && pterodactyl) {
            // No interactive console on Pterodactyl: use the panel's memory limit (MB) when known.
            String mem = env("SERVER_MEMORY", null);
            ram = (mem != null && mem.matches("[0-9]+") && Long.parseLong(mem) >= 1) ? mem + "M" : "2";
        }
        if (ram == null) {
            // Prompt, but never hang: give up after 15 seconds and use the default.
            String input = readLineWithTimeout("[setup] RAM for the server in GB (e.g. 2, 4) [2]: ", 15);
            ram = (input == null || input.trim().isEmpty()) ? "2" : input.trim();
        }
        ram = parseRam(ram);
        log("server RAM: " + ram + " (change it in run.sh / run.bat after setup)");

        if (Files.exists(serverDir.resolve("server.jar")) && !env("FORCE", "0").equals("1")) {
            throw new SetupException(serverDir + " already contains server.jar (use FORCE=1 to overwrite)");
        }

        log("server directory: " + serverDir);
        Path mods = serverDir.resolve("mods");
        Files.createDirectories(mods);

        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        // 1) Official Fabric server jar
        log("resolving Fabric loader/installer for Minecraft " + mcVersion);
        String loader = firstStableVersion(getString(http, "https://meta.fabricmc.net/v2/versions/loader"));
        String installer = firstStableVersion(getString(http, "https://meta.fabricmc.net/v2/versions/installer"));
        if (loader == null) throw new SetupException("could not resolve Fabric loader");
        if (installer == null) throw new SetupException("could not resolve Fabric installer");
        log("downloading Fabric server (loader " + loader + " / installer " + installer + ")");
        download(http, "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion + "/" + loader + "/" + installer + "/server/jar",
                serverDir.resolve("server.jar.new"));

        // 2) Fabric API (Modrinth)
        log("resolving Fabric API for Minecraft " + mcVersion);
        String apiJson = getString(http, "https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%22"
                + mcVersion + "%22%5D&loaders=%5B%22fabric%22%5D");
        String fabricApiUrl = modrinthFirstFileUrl(apiJson);
        if (fabricApiUrl == null) throw new SetupException("could not resolve Fabric API");
        log("downloading Fabric API");
        download(http, fabricApiUrl, mods.resolve("fabric-api.jar"));

        // 3) Time SMP mod
        log("downloading Time SMP mod (" + modAsset + " from tag " + modTag + ")");
        download(http, modUrl, mods.resolve("timesmp.jar"));

        // 3c) World generation mods
        if (is263) {
            log("downloading world generation mods (lithostitched + tectonic)");
            download(http, base + "lithostitched-2.0.6-fabric-26.3.jar", mods.resolve("lithostitched-2.0.6-fabric-26.3.jar"));
            download(http, base + "tectonic-3.0.32-fabric-26.3.jar", mods.resolve("tectonic-3.0.32-fabric-26.3.jar"));
        } else {
            log("skipping world generation mods (only published for 26.3)");
        }

        // 3d) Additional mods
        if (is263) {
            log("downloading additional mods (sit + otterlib)");
            download(http, base + "sit.-1.2.6.4+26.3.jar", mods.resolve("sit.-1.2.6.4+26.3.jar"));
            download(http, base + "otterlib-0.4.0.2+26.3-fabric.jar", mods.resolve("otterlib-0.4.0.2+26.3-fabric.jar"));
        } else {
            log("skipping additional mods (only published for 26.3)");
        }

        // 3f) Performance/utility mods bundle
        if (is263) {
            log("downloading performance mods bundle (modo.zip)");
            Path tmp = Files.createTempDirectory("timesmp-modo");
            try {
                Path zip = tmp.resolve("modo.zip");
                download(http, base + "modo.zip", zip);
                unzip(zip, mods);
            } finally {
                rmrf(tmp);
            }
            log("performance mods bundle installed into mods/");
        } else {
            log("skipping performance mods bundle (only published for 26.3)");
        }

        // 3e) Lobby world, 3g) Find world
        installWorld(http, "lobby", lobbyUrl, serverDir);
        installWorld(http, "find", findUrl, serverDir);

        // server.jar only appears once every step above succeeded, so a failed run can be re-run.
        Files.move(serverDir.resolve("server.jar.new"), serverDir.resolve("server.jar"),
                StandardCopyOption.REPLACE_EXISTING);

        // 4) EULA
        Files.writeString(serverDir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);

        // 5) server.properties
        log("writing server.properties");
        Files.writeString(serverDir.resolve("server.properties"),
                "#Minecraft server properties - Time SMP\n"
                        + "server-port=25565\n"
                        + "server-ip=\n"
                        + "motd=Time SMP\n"
                        + "max-players=5\n"
                        + "online-mode=false\n"
                        + "enforce-secure-profile=false\n"
                        + "white-list=false\n"
                        + "enforce-whitelist=false\n"
                        + "gamemode=survival\n"
                        + "force-gamemode=true\n"
                        + "hardcore=false\n"
                        + "difficulty=hard\n"
                        + "pvp=true\n"
                        + "level-name=world\n"
                        + "view-distance=10\n"
                        + "simulation-distance=6\n"
                        + "enable-status=true\n"
                        + "spawn-protection=0\n"
                        + "max-tick-time=180000\n",
                StandardCharsets.UTF_8);

        // 6) Launchers
        log("writing run.sh and run.bat (RAM=" + ram + ")");
        Path runSh = serverDir.resolve("run.sh");
        Files.writeString(runSh,
                "#!/usr/bin/env bash\n"
                        + "# Time SMP server launcher.\n"
                        + "# Edit RAM below to choose how much memory the server may use, for example:\n"
                        + "#   RAM=\"2G\"   # small server\n"
                        + "#   RAM=\"4G\"   # busy server with several worlds loaded\n"
                        + "# You can also override it when starting: RAM=8G ./run.sh\n"
                        + "RAM=\"${RAM:-" + ram + "}\"\n"
                        + "cd \"$(dirname \"$0\")\"\n"
                        + "exec java -Xms\"$RAM\" -Xmx\"$RAM\" -jar server.jar nogui\n",
                StandardCharsets.UTF_8);
        runSh.toFile().setExecutable(true, false);
        Files.writeString(serverDir.resolve("run.bat"),
                "@echo off\r\n"
                        + "rem Time SMP server launcher. Edit RAM below or run: set RAM=8G && run.bat\r\n"
                        + "if \"%RAM%\"==\"\" set RAM=" + ram + "\r\n"
                        + "cd /d \"%~dp0\"\r\n"
                        + "java -Xms%RAM% -Xmx%RAM% -jar server.jar nogui\r\n",
                StandardCharsets.UTF_8);

        // Done
        log("done");
        List<String> modNames = new ArrayList<>();
        try (Stream<Path> s = Files.list(mods)) {
            s.forEach(p -> modNames.add(p.getFileName().toString()));
        }
        modNames.sort(String::compareTo);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        System.out.println();
        System.out.println("Install dir : " + serverDir);
        System.out.println("Minecraft   : " + mcVersion + " (Fabric loader " + loader + ")");
        System.out.println("Java        : " + System.getProperty("java.version"));
        System.out.println("Mods        : " + String.join(" ", modNames));
        System.out.println();
        System.out.println("Start with:");
        System.out.println(windows
                ? "  cd \"" + serverDir + "\" && run.bat"
                : "  cd \"" + serverDir + "\" && ./run.sh");
        System.out.println();
        System.out.println("RAM is set in run.sh / run.bat (currently " + ram + ").");
        System.out.println();
        System.out.println("The server is OPEN (no whitelist) with no common/spawn area:");
        System.out.println("  players can claim land at every point (spawn-protection=0, spawnProtectedCells=0).");
        System.out.println();
        System.out.println("No admins are added automatically. Grant one in-game with:");
        System.out.println("  /op <player>");
    }

    static String readLineWithTimeout(String prompt, int seconds) {
        System.out.print(prompt);
        System.out.flush();
        final String[] result = new String[1];
        Thread t = new Thread(() -> {
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8));
                result[0] = r.readLine();
            } catch (IOException ignored) {
                // fall through to default
            }
        }, "ram-prompt");
        t.setDaemon(true);
        t.start();
        try {
            t.join(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            System.out.println();
            log("no answer after " + seconds + "s, using the default RAM");
            return null;
        }
        return result[0];
    }

    static void usage() {
        System.out.println("Time SMP server setup (pure Java)\n"
                + "Usage: java -jar timesmp-setup.jar [server-dir]   (default: ~/mcserver)\n"
                + "Env  : RAM=4G FORCE=1 MC_VERSION=26.3 REPO MOD_TAG MOD_URL WORLDGEN_RELEASE LOBBY_URL FIND_URL SERVER_DIR");
    }

    static String parseRam(String ram) {
        Matcher m = Pattern.compile("^([0-9]+)([GgMm]?)$").matcher(ram);
        if (!m.matches()) {
            throw new SetupException("invalid RAM '" + ram + "' (use a number of GB, e.g. 2, or a size like 4G)");
        }
        long n;
        try {
            n = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            throw new SetupException("invalid RAM '" + ram + "'");
        }
        if (n < 1) throw new SetupException("invalid RAM '" + ram + "' (must be at least 1G)");
        String unit = m.group(2).isEmpty() ? "G" : m.group(2).toUpperCase(Locale.ROOT);
        return n + unit;
    }

    // ---- worlds ------------------------------------------------------------

    static void installWorld(HttpClient http, String name, String url, Path serverDir) throws Exception {
        log("downloading " + name + " world");
        Path tmp = Files.createTempDirectory("timesmp-" + name);
        try {
            Path zip = tmp.resolve(name + ".zip");
            download(http, url, zip);
            Path extract = tmp.resolve("extract");
            Files.createDirectories(extract);
            unzip(zip, extract);
            Path src = extract.resolve(name);
            if (!Files.isDirectory(src)) {
                throw new SetupException("zip for the " + name + " world has no '" + name + "' folder");
            }
            Path worlds = serverDir.resolve("worlds");
            Files.createDirectories(worlds);
            Path dest = worlds.resolve(name);
            rmrf(dest);
            Files.move(src, dest);
            // Wipe player data so the world starts clean
            for (String d : new String[] {"players", "playerdata", "advancements", "stats"}) {
                rmrf(dest.resolve(d));
            }
            Files.deleteIfExists(dest.resolve("session.lock"));
        } finally {
            rmrf(tmp);
        }
        log(name + " world installed at worlds/" + name + " (player data wiped)");
    }

    // ---- JSON helpers ------------------------------------------------------

    @SuppressWarnings("unchecked")
    static String firstStableVersion(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof List)) return null;
        for (Object o : (List<Object>) root) {
            if (o instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) o;
                if (Boolean.TRUE.equals(m.get("stable")) && m.get("version") instanceof String) {
                    return (String) m.get("version");
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static String modrinthFirstFileUrl(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof List) || ((List<Object>) root).isEmpty()) return null;
        Object first = ((List<Object>) root).get(0);
        if (!(first instanceof Map)) return null;
        Object files = ((Map<String, Object>) first).get("files");
        if (!(files instanceof List) || ((List<Object>) files).isEmpty()) return null;
        Object f0 = ((List<Object>) files).get(0);
        if (!(f0 instanceof Map)) return null;
        Object url = ((Map<String, Object>) f0).get("url");
        return url instanceof String ? (String) url : null;
    }

    /** Minimal JSON parser (objects, arrays, strings, numbers, booleans, null). */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String s) {
            Json j = new Json(s);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != s.length()) throw new SetupException("invalid JSON (trailing data)");
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            if (i >= s.length()) throw new SetupException("invalid JSON (unexpected end)");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new SetupException("invalid JSON at position " + i);
            return Double.valueOf(s.substring(st, i));
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; // [
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect(']');
                return l;
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new SetupException("invalid JSON (unterminated string)");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: sb.append(e); // " \ /
                }
            }
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) {
                throw new SetupException("invalid JSON: expected '" + c + "' at position " + i);
            }
            i++;
        }
    }

    // ---- network / files ---------------------------------------------------

    static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "timesmp-setup")
                .timeout(Duration.ofMinutes(10))
                .GET().build();
    }

    static String getString(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(request(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() / 100 != 2) {
            throw new SetupException("request failed (HTTP " + r.statusCode() + "): " + url);
        }
        return r.body();
    }

    static void download(HttpClient http, String url, Path target) throws IOException, InterruptedException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        HttpResponse<InputStream> r = http.send(request(url), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() / 100 != 2) {
            r.body().close();
            throw new SetupException("download failed (HTTP " + r.statusCode() + "): " + url);
        }
        Path part = target.resolveSibling(target.getFileName() + ".part");
        long total = 0;
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf(Locale.ROOT, "  -> %s (%.1f MB)%n", target.getFileName(), total / 1048576.0);
    }

    /** Extract a zip into dest, overwriting existing files (zip-slip safe). */
    static void unzip(Path zip, Path dest) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) throw new SetupException("unsafe path in zip: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void rmrf(Path p) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.delete(x);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}

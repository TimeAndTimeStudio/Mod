package studio.timeandtime.setup;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Runnable wrapper around the bundled setup.sh.
 * Usage: java -jar timesmp-setup.jar [server-dir]
 * Environment variables (RAM, FORCE, MC_VERSION, ...) are passed through to the script.
 */
public final class SetupMain {
    public static void main(String[] args) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            System.err.println("[setup] setup.sh needs bash (Linux/macOS/WSL). Run this jar inside WSL.");
            System.exit(1);
        }
        Path script = Files.createTempFile("timesmp-setup-", ".sh");
        script.toFile().deleteOnExit();
        try (InputStream in = SetupMain.class.getResourceAsStream("/setup.sh")) {
            if (in == null) {
                System.err.println("[setup] setup.sh is missing from the jar");
                System.exit(1);
            }
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        script.toFile().setExecutable(true);

        List<String> cmd = new ArrayList<>();
        cmd.add("bash");
        cmd.add(script.toString());
        for (String a : args) cmd.add(a);

        Process p = new ProcessBuilder(cmd).inheritIO().start();
        System.exit(p.waitFor());
    }
}

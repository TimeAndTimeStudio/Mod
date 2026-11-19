package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server dimension and feature settings loaded from {@code config/timesmp/setting.json}
 * (or {@code setting.json} in root).
 */
public final class ServerSettings {
	private static final Logger LOGGER = LoggerFactory.getLogger("timesmp-settings");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path file;

	public boolean netherEnabled = true;
	public boolean endEnabled = true;
	public boolean backupEnabled = true;
	public String backupPath = "backups";
	public int maxBackups = 10;

	public ServerSettings() {
		Path configDir = FabricLoader.getInstance().getConfigDir().resolve("timesmp");
		this.file = configDir.resolve("setting.json");
		load();
	}

	public ServerSettings(Path file) {
		this.file = file;
		load();
	}

	public void load() {
		if (file == null) return;
		if (!Files.exists(file)) {
			save(); // create default setting.json
			return;
		}
		try {
			String content = Files.readString(file, StandardCharsets.UTF_8);
			JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
			if (obj.has("nether")) {
				this.netherEnabled = obj.get("nether").getAsBoolean();
			} else if (obj.has("the_nether")) {
				this.netherEnabled = obj.get("the_nether").getAsBoolean();
			}
			if (obj.has("end")) {
				this.endEnabled = obj.get("end").getAsBoolean();
			} else if (obj.has("the_end")) {
				this.endEnabled = obj.get("the_end").getAsBoolean();
			}

			if (obj.has("backup") && obj.get("backup").isJsonObject()) {
				JsonObject b = obj.getAsJsonObject("backup");
				if (b.has("enabled")) this.backupEnabled = b.get("enabled").getAsBoolean();
				if (b.has("path")) this.backupPath = b.get("path").getAsString();
				if (b.has("max_backups")) this.maxBackups = b.get("max_backups").getAsInt();
			} else {
				if (obj.has("backup_enabled")) this.backupEnabled = obj.get("backup_enabled").getAsBoolean();
				if (obj.has("backup_path")) this.backupPath = obj.get("backup_path").getAsString();
				if (obj.has("max_backups")) this.maxBackups = obj.get("max_backups").getAsInt();
			}

			LOGGER.info("[settings] Loaded setting.json: nether={}, end={}, backup=[enabled={}, path={}, max={}]",
					netherEnabled, endEnabled, backupEnabled, backupPath, maxBackups);
		} catch (Exception e) {
			LOGGER.warn("[settings] Could not read setting.json: {}, using defaults", e.getMessage());
		}
	}

	public void save() {
		if (file == null) return;
		try {
			Path parent = file.getParent();
			if (parent != null) Files.createDirectories(parent);
			JsonObject obj = new JsonObject();
			obj.addProperty("nether", netherEnabled);
			obj.addProperty("end", endEnabled);

			JsonObject b = new JsonObject();
			b.addProperty("enabled", backupEnabled);
			b.addProperty("path", backupPath);
			b.addProperty("max_backups", maxBackups);
			obj.add("backup", b);

			Files.writeString(file, GSON.toJson(obj), StandardCharsets.UTF_8);
			LOGGER.info("[settings] Saved default setting.json to {}", file);
		} catch (IOException e) {
			LOGGER.warn("[settings] Could not write setting.json: {}", e.getMessage());
		}
	}

	public boolean isNetherEnabled() {
		return netherEnabled;
	}

	public boolean isEndEnabled() {
		return endEnabled;
	}
}

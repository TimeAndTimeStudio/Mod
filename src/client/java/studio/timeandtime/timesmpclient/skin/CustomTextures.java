package studio.timeandtime.timesmpclient.skin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import studio.timeandtime.timesmpclient.TimeSmpClient;

/** Builds textures for the data:/Ely.by URLs TimeSMP's local skin files use. */
public final class CustomTextures {
	private static final Gson GSON = new Gson();

	private CustomTextures() {
	}

	public static MinecraftProfileTextures unpack(Property property) {
		if (property == null || property.value() == null || property.value().isEmpty()) return null;
		try {
			String json = new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8);
			JsonObject root = GSON.fromJson(json, JsonObject.class);
			if (root == null || !root.has("textures")) return null;
			JsonObject textures = root.getAsJsonObject("textures");

			MinecraftProfileTexture skin = texture(textures, "SKIN");
			MinecraftProfileTexture cape = texture(textures, "CAPE");
			MinecraftProfileTexture elytra = texture(textures, "ELYTRA");
			if (!isCustom(skin) && !isCustom(cape) && !isCustom(elytra)) return null;

			TimeSmpClient.LOGGER.info("[timesmp-client] Applying custom textures (skin={}, cape={}, elytra={})",
					skin != null, cape != null, elytra != null);
			return new MinecraftProfileTextures(skin, cape, elytra, SignatureState.SIGNED);
		} catch (Exception ignored) {
			return null;
		}
	}

	private static MinecraftProfileTexture texture(JsonObject textures, String kind) {
		if (!textures.has(kind)) return null;
		JsonObject entry = textures.getAsJsonObject(kind);
		if (!entry.has("url")) return null;
		String url = entry.get("url").getAsString();
		if (url == null || url.isEmpty()) return null;
		Map<String, String> metadata = new HashMap<>();
		if (entry.has("metadata")) {
			JsonObject meta = entry.getAsJsonObject("metadata");
			for (String key : meta.keySet()) metadata.put(key, meta.get(key).getAsString());
		}
		return new MinecraftProfileTexture(url, metadata);
	}

	private static boolean isCustom(MinecraftProfileTexture texture) {
		if (texture == null || texture.getUrl() == null) return false;
		String url = texture.getUrl();
		return url.startsWith("data:image/")
				|| url.startsWith("http://skinsystem.ely.by/")
				|| url.startsWith("https://skinsystem.ely.by/")
				|| url.startsWith("http://ely.by/")
				|| url.startsWith("https://ely.by/");
	}
}

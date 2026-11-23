package studio.timeandtime.timesmpclient.mixin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Lets TimeSMP's server-side skins reach the vanilla renderer.
 *
 * <p>The server sends local files as {@code data:image/png;base64,...} and
 * Ely.by textures as plain URLs, both without a Mojang signature. Vanilla
 * would otherwise drop them for other players in online mode
 * ({@code requireSecure}). We only promote URLs the server controls, so an
 * unsigned Mojang URL or an arbitrary third-party URL is still rejected by
 * vanilla.</p>
 */
@Mixin(SkinManager.class)
public abstract class SkinManagerMixin {
	@Unique
	private static final Gson timesmp$GSON = new Gson();

	@Inject(method = "get", at = @At("HEAD"), cancellable = true)
	private void timesmp$getServerSkin(GameProfile profile,
			CallbackInfoReturnable<CompletableFuture<Optional<PlayerSkin>>> cir) {
		if (profile == null || profile.id() == null) return;

		Property texturesProp = profile.properties().get("textures").stream().findFirst().orElse(null);
		if (texturesProp == null || texturesProp.value() == null || texturesProp.value().isEmpty()) {
			return;
		}

		try {
			String jsonStr = new String(Base64.getDecoder().decode(texturesProp.value()), StandardCharsets.UTF_8);
			JsonObject json = timesmp$GSON.fromJson(jsonStr, JsonObject.class);
			if (json == null || !json.has("textures")) return;
			JsonObject textures = json.getAsJsonObject("textures");

			MinecraftProfileTexture skin = timesmp$texture(textures, "SKIN");
			MinecraftProfileTexture cape = timesmp$texture(textures, "CAPE");
			MinecraftProfileTexture elytra = timesmp$texture(textures, "ELYTRA");

			if (skin == null && cape == null && elytra == null) return;
			if (!timesmp$isCustom(skin) && !timesmp$isCustom(cape) && !timesmp$isCustom(elytra)) {
				// Not one of our skins: let vanilla verify its signature.
				return;
			}

			MinecraftProfileTextures parsed =
					new MinecraftProfileTextures(skin, cape, elytra, SignatureState.SIGNED);
			CompletableFuture<PlayerSkin> future =
					((SkinManagerInvoker) (Object) this).timesmp$registerTextures(profile.id(), parsed);
			cir.setReturnValue(future.thenApply(Optional::of));
		} catch (Exception ignored) {
			// A damaged property falls through to vanilla's own handling.
		}
	}

	@Unique
	private static MinecraftProfileTexture timesmp$texture(JsonObject textures, String kind) {
		if (!textures.has(kind)) return null;
		JsonObject entry = textures.getAsJsonObject(kind);
		if (!entry.has("url")) return null;
		String url = entry.get("url").getAsString();
		if (url == null || url.isEmpty()) return null;
		Map<String, String> meta = new HashMap<>();
		if (entry.has("metadata")) {
			JsonObject m = entry.getAsJsonObject("metadata");
			for (String key : m.keySet()) {
				meta.put(key, m.get(key).getAsString());
			}
		}
		return new MinecraftProfileTexture(url, meta);
	}

	@Unique
	private static boolean timesmp$isCustom(MinecraftProfileTexture texture) {
		if (texture == null) return false;
		String url = texture.getUrl();
		if (url == null) return false;
		return url.startsWith("data:image/")
				|| url.startsWith("http://skinsystem.ely.by/")
				|| url.startsWith("https://skinsystem.ely.by/")
				|| url.startsWith("http://ely.by/")
				|| url.startsWith("https://ely.by/");
	}
}
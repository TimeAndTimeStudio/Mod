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
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Mixin(SkinManager.class)
public abstract class SkinManagerMixin {
	@Unique
	private static final Gson timesmp$GSON = new Gson();

	@Shadow
	protected abstract CompletableFuture<PlayerSkin> registerTextures(UUID uuid, MinecraftProfileTextures textures);

	@Inject(method = "get", at = @At("HEAD"), cancellable = true)
	private void onGetProfileSkin(GameProfile profile, CallbackInfoReturnable<CompletableFuture<Optional<PlayerSkin>>> cir) {
		if (profile == null) return;

		Property texturesProp = profile.properties().get("textures").stream().findFirst().orElse(null);
		if (texturesProp == null || texturesProp.value() == null || texturesProp.value().isEmpty()) {
			return;
		}

		try {
			String jsonStr = new String(Base64.getDecoder().decode(texturesProp.value()), StandardCharsets.UTF_8);
			JsonObject json = timesmp$GSON.fromJson(jsonStr, JsonObject.class);
			if (json != null && json.has("textures")) {
				JsonObject textures = json.getAsJsonObject("textures");
				MinecraftProfileTexture skin = null;
				MinecraftProfileTexture cape = null;
				MinecraftProfileTexture elytra = null;

				if (textures.has("SKIN")) {
					JsonObject s = textures.getAsJsonObject("SKIN");
					String url = s.has("url") ? s.get("url").getAsString() : null;
					Map<String, String> meta = new HashMap<>();
					if (s.has("metadata")) {
						JsonObject m = s.getAsJsonObject("metadata");
						for (String k : m.keySet()) {
							meta.put(k, m.get(k).getAsString());
						}
					}
					if (url != null) {
						skin = new MinecraftProfileTexture(url, meta);
					}
				}

				if (textures.has("CAPE")) {
					JsonObject c = textures.getAsJsonObject("CAPE");
					String url = c.has("url") ? c.get("url").getAsString() : null;
					if (url != null) {
						cape = new MinecraftProfileTexture(url, null);
					}
				}

				if (textures.has("ELYTRA")) {
					JsonObject e = textures.getAsJsonObject("ELYTRA");
					String url = e.has("url") ? e.get("url").getAsString() : null;
					if (url != null) {
						elytra = new MinecraftProfileTexture(url, null);
					}
				}

				if (skin != null || cape != null || elytra != null) {
					MinecraftProfileTextures parsed = new MinecraftProfileTextures(skin, cape, elytra, SignatureState.SIGNED);
					CompletableFuture<Optional<PlayerSkin>> future = registerTextures(profile.id(), parsed)
							.thenApply(Optional::of);
					cir.setReturnValue(future);
				}
			}
		} catch (Exception ignored) {
		}
	}
}

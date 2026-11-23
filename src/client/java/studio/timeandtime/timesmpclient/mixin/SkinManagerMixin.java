package studio.timeandtime.timesmpclient.mixin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Mixin(targets = "net.minecraft.client.resources.SkinManager$1")
public class SkinManagerMixin {
	@Unique
	private static final Gson timesmp$GSON = new Gson();

	@Inject(method = "lambda$load$0", at = @At("HEAD"), cancellable = true, remap = false)
	private static void unpackCustomSkinTextures(Object cacheKeyObj, Object servicesObj, CallbackInfoReturnable<MinecraftProfileTextures> cir) {
		try {
			if (cacheKeyObj != null) {
				Method packedTexturesMethod = cacheKeyObj.getClass().getMethod("packedTextures");
				packedTexturesMethod.setAccessible(true);
				Property property = (Property) packedTexturesMethod.invoke(cacheKeyObj);
				if (property == null || property.value() == null || property.value().isEmpty()) {
					return;
				}

				String jsonStr = new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8);
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
						cir.setReturnValue(new MinecraftProfileTextures(skin, cape, elytra, SignatureState.SIGNED));
					}
				}
			}
		} catch (Exception ignored) {
			// Fall back to vanilla unpackTextures if parsing fails
		}
	}
}

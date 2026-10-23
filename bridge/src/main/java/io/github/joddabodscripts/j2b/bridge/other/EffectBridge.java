package io.github.joddabodscripts.j2b.bridge.other;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.core.api.other.PolymerMobEffect;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import io.github.joddabodscripts.j2b.bridge.J2bBridge;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Shows modded effects in a vanilla client's effect list.
 *
 * <p>The client only knows the effects built into it, so each modded effect borrows one that a
 * vanilla client does nothing with except list it, like Luck. The resource pack then gives the
 * borrowed effect the modded effect's icon and name. The catch is that a player who really has
 * Luck sees it under the modded name.
 */
public final class EffectBridge {
	/** Effects with no client-side behaviour, rarest in survival first. */
	private static final List<Holder<MobEffect>> DONORS = List.of(
			MobEffects.LUCK, MobEffects.UNLUCK, MobEffects.HERO_OF_THE_VILLAGE,
			MobEffects.WEAVING, MobEffects.OOZING, MobEffects.INFESTED, MobEffects.WIND_CHARGED);
	private static final Gson GSON = new Gson();

	/** Modded effect id to the vanilla effect it shows as. */
	private static final Map<Identifier, Identifier> BORROWED = new LinkedHashMap<>();

	public static void bridge(MobEffect effect) {
		Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(effect);
		if (BORROWED.size() >= DONORS.size()) {
			J2bBridge.LOGGER.warn("No vanilla effect left to show {} as. It will be hidden from vanilla clients.", id);
			PolymerMobEffect.registerOverlay(effect);
			return;
		}
		Holder<MobEffect> donor = DONORS.get(BORROWED.size());
		BORROWED.put(id, donor.unwrapKey().orElseThrow().identifier());
		PolymerMobEffect.registerOverlay(effect, (e, context) -> donor.value());
	}

	/** Copies each modded effect's icon over the effect it borrows. Runs once the pack is filled. */
	public static void addIcons(ResourcePackBuilder builder) {
		BORROWED.forEach((effect, donor) -> {
			byte[] icon = builder.getDataOrSource("assets/" + effect.getNamespace() + "/textures/mob_effect/" + effect.getPath() + ".png");
			if (icon != null) {
				builder.addData("assets/minecraft/textures/mob_effect/" + donor.getPath() + ".png", icon);
			}
		});
	}

	/**
	 * Gives each borrowed effect the modded effect's name in every language. This has to run
	 * before the pack is built, since Polymer merges language files early.
	 */
	public static void addNames(ResourcePackBuilder builder) {
		Map<String, JsonObject> langs = new HashMap<>();
		for (Map.Entry<Identifier, Identifier> entry : BORROWED.entrySet()) {
			Identifier effect = entry.getKey();
			Identifier donor = entry.getValue();
			String key = "effect." + effect.getNamespace() + "." + effect.getPath();
			String donorKey = "effect.minecraft." + donor.getPath();
			langFiles(effect.getNamespace()).forEach((file, lang) -> {
				if (lang.has(key)) {
					langs.computeIfAbsent(file, f -> new JsonObject()).add(donorKey, lang.get(key));
				}
			});
		}

		langs.forEach((file, lang) -> builder.addData("assets/minecraft/lang/" + file,
				GSON.toJson(lang).getBytes(StandardCharsets.UTF_8)));
	}

	/** Every language file the mod ships, read from its jar. */
	private static Map<String, JsonObject> langFiles(String namespace) {
		Map<String, JsonObject> files = new HashMap<>();
		FabricLoader.getInstance().getAllMods().forEach(mod -> mod.getRootPaths().forEach(root -> {
			Path dir = root.resolve("assets/" + namespace + "/lang");
			if (!Files.isDirectory(dir)) {
				return;
			}
			try (Stream<Path> paths = Files.list(dir)) {
				paths.filter(path -> path.toString().endsWith(".json")).forEach(path -> {
					try {
						files.put(path.getFileName().toString(),
								JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject());
					} catch (IOException | RuntimeException e) {
						J2bBridge.LOGGER.debug("Skipping language file {}", path, e);
					}
				});
			} catch (IOException e) {
				J2bBridge.LOGGER.debug("Could not list {}", dir, e);
			}
		}));
		return files;
	}

	private EffectBridge() {
	}
}

package io.github.joddabodscripts.j2b.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Reads client assets (block states, models) straight out of the loaded mod jars. */
public final class Assets {
	private static final Map<String, Optional<JsonElement>> CACHE = new HashMap<>();

	public static Optional<JsonElement> json(String path) {
		return CACHE.computeIfAbsent(path, Assets::load);
	}

	private static Optional<JsonElement> load(String path) {
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			for (Path root : mod.getRootPaths()) {
				Path file = root.resolve(path);
				if (Files.isRegularFile(file)) {
					try {
						return Optional.of(JsonParser.parseString(Files.readString(file)));
					} catch (IOException | RuntimeException e) {
						J2bBridge.LOGGER.warn("Could not read {} from {}", path, mod.getMetadata().getId(), e);
					}
				}
			}
		}
		return Optional.empty();
	}

	private Assets() {
	}
}

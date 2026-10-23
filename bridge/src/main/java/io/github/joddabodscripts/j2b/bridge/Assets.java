package io.github.joddabodscripts.j2b.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import io.github.joddabodscripts.j2b.bridge.client.ClientClasses;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads client assets (block states, models) straight out of the loaded mod jars, and the game's
 * own out of the vanilla client jar Polymer keeps.
 */
public final class Assets {
	private static final Map<String, Optional<JsonElement>> CACHE = new HashMap<>();
	private static @Nullable FileSystem vanilla;

	public static synchronized Optional<JsonElement> json(String path) {
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
		return loadVanilla(path);
	}

	private static Optional<JsonElement> loadVanilla(String path) {
		try {
			if (vanilla == null) {
				Optional<Path> jar = ClientClasses.clientJar();
				if (jar.isEmpty()) {
					return Optional.empty();
				}
				vanilla = FileSystems.newFileSystem(jar.get());
			}
			Path file = vanilla.getPath(path);
			return Files.isRegularFile(file) ? Optional.of(JsonParser.parseString(Files.readString(file))) : Optional.empty();
		} catch (IOException | RuntimeException e) {
			J2bBridge.LOGGER.warn("Could not read {} from the vanilla client jar", path, e);
			return Optional.empty();
		}
	}

	private Assets() {
	}
}

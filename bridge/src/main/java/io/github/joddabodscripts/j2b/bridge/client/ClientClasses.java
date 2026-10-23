package io.github.joddabodscripts.j2b.bridge.client;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Puts the game's client classes on the server's class path, so mods' client renderers can run.
 *
 * <p>A dedicated server only has the server half of the game. Polymer already keeps a copy of the
 * vanilla client jar for building the resource pack; the classes the server jar lacks are copied
 * out of it and added before Minecraft starts. Nothing here draws anything: the classes are only
 * used to run renderers and read their models.
 */
public final class ClientClasses implements PreLaunchEntrypoint {
	private static final Logger LOGGER = LoggerFactory.getLogger("j2bbridge");
	private static boolean available;

	/** Whether the client classes loaded. When false, mods' renderers are skipped. */
	public static boolean available() {
		return available;
	}

	@Override
	public void onPreLaunch() {
		try {
			Optional<Path> classes = clientOnlyClasses();
			if (classes.isPresent()) {
				FabricLauncherBase.getLauncher().addToClassPath(classes.get());
				available = true;
			}
		} catch (Exception e) {
			LOGGER.warn("Could not set up the client classes, mods' block entity renderers won't run: {}", e.toString());
		}
	}

	private static Optional<Path> clientOnlyClasses() throws IOException {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		ModContainer minecraft = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow();
		String version = minecraft.getMetadata().getVersion().getFriendlyString();
		Path out = gameDir.resolve("j2b/client-classes-" + version + ".jar");
		if (Files.isRegularFile(out)) {
			return Optional.of(out);
		}
		Optional<Path> clientJar = polymerClientJar(gameDir);
		if (clientJar.isEmpty()) {
			LOGGER.warn("No vanilla client jar in polymer/cached_client_jars yet. Mods' block entity renderers will run after the next restart.");
			return Optional.empty();
		}

		Set<String> serverClasses = new HashSet<>();
		for (Path root : minecraft.getRootPaths()) {
			try (Stream<Path> files = Files.walk(root)) {
				files.map(root::relativize).map(Path::toString).filter(name -> name.endsWith(".class")).forEach(serverClasses::add);
			}
		}

		Files.createDirectories(out.getParent());
		Path temp = out.resolveSibling(out.getFileName() + ".tmp");
		int count = 0;
		try (FileSystem client = FileSystems.newFileSystem(clientJar.get());
			 ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
			Path root = client.getPath("/");
			try (Stream<Path> files = Files.walk(root)) {
				for (Path file : (Iterable<Path>) files::iterator) {
					String name = root.relativize(file).toString();
					if (name.endsWith(".class") && !serverClasses.contains(name)) {
						zip.putNextEntry(new ZipEntry(name));
						Files.copy(file, zip);
						zip.closeEntry();
						count++;
					}
				}
			}
		}
		Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING);
		LOGGER.info("Copied {} client classes for running mods' renderers on the server", count);
		return Optional.of(out);
	}

	/** Polymer saves the vanilla client jar here when it first builds the resource pack. */
	private static Optional<Path> polymerClientJar(Path gameDir) throws IOException {
		Path dir = gameDir.resolve("polymer/cached_client_jars");
		if (!Files.isDirectory(dir)) {
			return Optional.empty();
		}
		try (Stream<Path> jars = Files.list(dir)) {
			return jars.filter(path -> path.toString().endsWith(".jar")).findFirst();
		}
	}
}

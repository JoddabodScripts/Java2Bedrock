package io.github.joddabodscripts.j2b.bridge.client;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
	/** Bump when the copied classes change, so an older copy gets replaced. */
	private static final int FORMAT = 2;
	/**
	 * Interfaces Fabric's client modules add to game classes, which a server doesn't get. The
	 * copies here extend a server version of each instead.
	 */
	private static final Map<String, String> ADDED_INTERFACES = Map.of(
			"net/minecraft/client/renderer/block/dispatch/BlockStateModel.class", "io/github/joddabodscripts/j2b/bridge/client/ServerBlockStateModel",
			"net/minecraft/client/renderer/block/dispatch/BlockStateModelPart.class", "io/github/joddabodscripts/j2b/bridge/client/ServerBlockStateModelPart");
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
				for (Path jar : clientOnlyLibraries()) {
					FabricLauncherBase.getLauncher().addToClassPath(jar);
				}
				available = true;
			}
			if (!Boolean.getBoolean("fabric.disableEnvironmentStrip")) {
				LOGGER.warn("Start the server with -Dfabric.disableEnvironmentStrip=true so mods' client setup can run and their mobs can be drawn");
			}
		} catch (Exception e) {
			LOGGER.warn("Could not set up the client classes, mods' block entity renderers won't run: {}", e.toString());
		}
	}

	private static Optional<Path> clientOnlyClasses() throws IOException {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		ModContainer minecraft = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow();
		String version = minecraft.getMetadata().getVersion().getFriendlyString();
		Path out = gameDir.resolve("j2b/client-classes-" + version + "-" + FORMAT + ".jar");
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
						byte[] bytes = Files.readAllBytes(file);
						String added = ADDED_INTERFACES.get(name);
						zip.putNextEntry(new ZipEntry(name));
						zip.write(added == null ? bytes : addInterface(bytes, added));
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

	private static byte[] addInterface(byte[] bytes, String added) {
		ClassReader reader = new ClassReader(bytes);
		ClassWriter writer = new ClassWriter(reader, 0);
		reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public void visit(int version, int access, String name, @Nullable String signature, String superName, String[] interfaces) {
				String[] more = Arrays.copyOf(interfaces, interfaces.length + 1);
				more[interfaces.length] = added;
				super.visit(version, access, name, signature == null ? null : signature + "L" + added + ";", superName, more);
			}
		}, 0);
		return writer.toByteArray();
	}

	/**
	 * Client-only parts of mods that a dedicated server leaves out, such as Fabric API's model
	 * loading and screen modules. Mods' client setup calls them, so they go on the class path too.
	 * Nothing calls their client hooks, so they don't do anything else.
	 */
	private static List<Path> clientOnlyLibraries() throws IOException {
		Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
		Path out = FabricLoader.getInstance().getGameDir().resolve("j2b/client-libraries");
		List<Path> result = new ArrayList<>();
		if (!Files.isDirectory(modsDir)) {
			return result;
		}
		Files.createDirectories(out);
		try (Stream<Path> mods = Files.list(modsDir)) {
			for (Path mod : (Iterable<Path>) mods.filter(path -> path.toString().endsWith(".jar"))::iterator) {
				try (FileSystem jar = FileSystems.newFileSystem(mod)) {
					Path nested = jar.getPath("META-INF/jars");
					if (!Files.isDirectory(nested)) {
						continue;
					}
					try (Stream<Path> inner = Files.list(nested)) {
						for (Path library : (Iterable<Path>) inner::iterator) {
							if (isClientOnly(library)) {
								Path copy = out.resolve(library.getFileName().toString());
								if (!Files.isRegularFile(copy)) {
									Files.copy(library, copy);
								}
								result.add(copy);
							}
						}
					}
				}
			}
		}
		return result;
	}

	private static boolean isClientOnly(Path jar) {
		try (FileSystem fs = FileSystems.newFileSystem(jar)) {
			Path json = fs.getPath("fabric.mod.json");
			if (!Files.isRegularFile(json)) {
				return false;
			}
			var metadata = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
			return metadata.has("environment") && metadata.get("environment").getAsString().equals("client");
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/** The vanilla client jar, once Polymer has saved it. */
	public static Optional<Path> clientJar() throws IOException {
		return polymerClientJar(FabricLoader.getInstance().getGameDir());
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

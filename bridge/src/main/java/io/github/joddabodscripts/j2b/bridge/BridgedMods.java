package io.github.joddabodscripts.j2b.bridge;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Decides which registry namespaces belong to mods the bridge should convert.
 *
 * <p>A namespace is bridged when it belongs to a loaded mod that does not already talk to
 * vanilla clients itself. Mods that depend on Polymer are left alone, since they set up
 * their own client-side replacements.
 */
public final class BridgedMods {
	private static final Set<String> NEVER = Set.of(
			"minecraft", "brigadier", "java", "fabricloader", "fabric-api", "c", J2bBridge.MOD_ID);

	private static final Set<String> bridged = new HashSet<>();
	private static final Set<String> skipped = new HashSet<>();
	private static boolean loaded;

	static synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			if (NEVER.contains(id) || id.startsWith("fabric") || id.startsWith("polymer") || handlesVanillaClients(mod)) {
				skipped.add(id);
			}
		}
	}

	private static boolean handlesVanillaClients(ModContainer mod) {
		for (ModDependency dependency : mod.getMetadata().getDependencies()) {
			if (dependency.getKind() == ModDependency.Kind.DEPENDS && dependency.getModId().startsWith("polymer")) {
				return true;
			}
		}
		return false;
	}

	/** True when content in this namespace should be converted for vanilla clients. */
	public static boolean isBridged(String namespace) {
		if (bridged.contains(namespace)) {
			return true;
		}
		if (NEVER.contains(namespace) || skipped.contains(namespace)) {
			return false;
		}
		// Content in a namespace without a matching mod id is usually a mod registering under
		// a shorter name. Treat it as bridged rather than leave it to kick players.
		bridged.add(namespace);
		return true;
	}

	/** True when the class was loaded from the jar of a mod the bridge converts. */
	public static boolean ownsClass(Class<?> type) {
		try {
			Path location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				if (mod.getOrigin().getKind() != ModOrigin.Kind.PATH) {
					continue;
				}
				for (Path path : mod.getOrigin().getPaths()) {
					if (path.toAbsolutePath().normalize().equals(location)) {
						return isBridged(mod.getMetadata().getId());
					}
				}
			}
		} catch (Exception e) {
			J2bBridge.LOGGER.debug("Could not find which mod {} comes from", type, e);
		}
		return false;
	}

	public static Set<String> namespaces() {
		return bridged;
	}

	public static Optional<ModContainer> container(String namespace) {
		return FabricLoader.getInstance().getModContainer(namespace);
	}

	private BridgedMods() {
	}
}

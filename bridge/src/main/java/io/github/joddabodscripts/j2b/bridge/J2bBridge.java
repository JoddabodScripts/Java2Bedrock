package io.github.joddabodscripts.j2b.bridge;

import io.github.joddabodscripts.j2b.bridge.block.SignEditing;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns content from ordinary Fabric mods into something a vanilla client can join with.
 *
 * <p>The mods themselves are not changed. Once every mod has registered its content, and before
 * the registries freeze, the bridge walks them and attaches Polymer overlays to whatever a vanilla
 * client would not know. See {@code BuiltInRegistriesMixin}.
 */
public class J2bBridge implements ModInitializer {
	public static final String MOD_ID = "j2bbridge";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		BridgedMods.load();
		SignEditing.register();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}

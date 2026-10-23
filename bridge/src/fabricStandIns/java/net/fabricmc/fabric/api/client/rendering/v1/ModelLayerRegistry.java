package net.fabricmc.fabric.api.client.rendering.v1;

import com.google.common.collect.ImmutableMap;
import io.github.joddabodscripts.j2b.bridge.client.ClientRegistrations;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.ArmorModelSet;

/** Server stand-in for Fabric's client-only registry. It records what mods register. */
public final class ModelLayerRegistry {
	public static void registerModelLayer(ModelLayerLocation location, TexturedLayerDefinitionProvider provider) {
		ClientRegistrations.layer(location, provider::createLayerDefinition);
	}

	public static void registerArmorModelLayers(ArmorModelSet<ModelLayerLocation> locations, TexturedArmorModelSetProvider provider) {
		ImmutableMap.Builder<ModelLayerLocation, LayerDefinition> layers = ImmutableMap.builder();
		locations.putFrom(provider.createArmorModelSet(), layers);
		layers.build().forEach((location, definition) -> ClientRegistrations.layer(location, () -> definition));
	}

	@FunctionalInterface
	public interface TexturedLayerDefinitionProvider {
		LayerDefinition createLayerDefinition();
	}

	@FunctionalInterface
	public interface TexturedArmorModelSetProvider {
		ArmorModelSet<LayerDefinition> createArmorModelSet();
	}

	private ModelLayerRegistry() {
	}
}

package io.github.joddabodscripts.j2b.bridge.render;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/** Reads the texture a render type samples, which the client keeps private. */
final class RenderTypes {
	private static final String MAIN_SAMPLER = "Sampler0";

	static @Nullable Identifier texture(RenderType renderType) {
		try {
			Field stateField = RenderType.class.getDeclaredField("state");
			stateField.setAccessible(true);
			Object state = stateField.get(renderType);
			Field texturesField = state.getClass().getDeclaredField("textures");
			texturesField.setAccessible(true);
			Object binding = ((Map<?, ?>) texturesField.get(state)).get(MAIN_SAMPLER);
			if (binding == null) {
				return null;
			}
			Method location = binding.getClass().getDeclaredMethod("location");
			location.setAccessible(true);
			return (Identifier) location.invoke(binding);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	private RenderTypes() {
	}
}

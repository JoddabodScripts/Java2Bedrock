package io.github.joddabodscripts.j2b.bridge.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.joddabodscripts.j2b.bridge.Assets;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Works out which models a block state uses by reading the mod's blockstate file, the same way
 * the client would.
 */
public final class BlockModels {
	/** One model reference from a blockstate file. */
	public record ModelRef(Identifier model, int x, int y, boolean uvLock, int weight) {
	}

	/**
	 * The models drawn for a state. Each inner list is one part. A plain variant file gives one part,
	 * a multipart file gives one part per matching case. Inside a part the entries are random
	 * alternatives picked by weight.
	 */
	public static List<List<ModelRef>> forState(BlockState state) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		String path = "assets/" + id.getNamespace() + "/blockstates/" + id.getPath() + ".json";
		JsonObject root = Assets.json(path).filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElse(null);
		if (root == null) {
			return List.of();
		}

		List<List<ModelRef>> parts = new ArrayList<>();
		if (root.has("variants")) {
			for (Map.Entry<String, JsonElement> variant : root.getAsJsonObject("variants").entrySet()) {
				if (matchesVariantKey(state, variant.getKey())) {
					parts.add(refs(variant.getValue()));
					break;
				}
			}
		} else if (root.has("multipart")) {
			for (JsonElement part : root.getAsJsonArray("multipart")) {
				JsonObject obj = part.getAsJsonObject();
				if (!obj.has("when") || matchesCondition(state, obj.getAsJsonObject("when"))) {
					parts.add(refs(obj.get("apply")));
				}
			}
		}
		parts.removeIf(List::isEmpty);
		return parts;
	}

	private static List<ModelRef> refs(JsonElement element) {
		List<ModelRef> refs = new ArrayList<>();
		if (element.isJsonArray()) {
			for (JsonElement e : element.getAsJsonArray()) {
				refs.add(ref(e.getAsJsonObject()));
			}
		} else {
			refs.add(ref(element.getAsJsonObject()));
		}
		return refs;
	}

	private static ModelRef ref(JsonObject obj) {
		return new ModelRef(
				Identifier.parse(GsonHelper.getAsString(obj, "model")),
				GsonHelper.getAsInt(obj, "x", 0),
				GsonHelper.getAsInt(obj, "y", 0),
				GsonHelper.getAsBoolean(obj, "uvlock", false),
				GsonHelper.getAsInt(obj, "weight", 1));
	}

	/** Variant keys look like {@code facing=north,lit=true}. An empty key matches every state. */
	private static boolean matchesVariantKey(BlockState state, String key) {
		if (key.isEmpty() || key.equals("normal")) {
			return true;
		}
		for (String pair : key.split(",")) {
			String[] kv = pair.split("=", 2);
			if (kv.length != 2 || !valueMatches(state, kv[0], kv[1])) {
				return false;
			}
		}
		return true;
	}

	private static boolean matchesCondition(BlockState state, JsonObject when) {
		if (when.has("OR")) {
			return anyMatch(state, when.getAsJsonArray("OR"));
		}
		if (when.has("AND")) {
			for (JsonElement e : when.getAsJsonArray("AND")) {
				if (!matchesCondition(state, e.getAsJsonObject())) {
					return false;
				}
			}
			return true;
		}
		for (Map.Entry<String, JsonElement> entry : when.entrySet()) {
			String expected = entry.getValue().getAsString();
			boolean negate = expected.startsWith("!");
			if (negate) {
				expected = expected.substring(1);
			}
			boolean any = false;
			for (String option : expected.split("\\|")) {
				any |= valueMatches(state, entry.getKey(), option);
			}
			if (any == negate) {
				return false;
			}
		}
		return true;
	}

	private static boolean anyMatch(BlockState state, JsonArray options) {
		for (JsonElement e : options) {
			if (matchesCondition(state, e.getAsJsonObject())) {
				return true;
			}
		}
		return false;
	}

	private static boolean valueMatches(BlockState state, String propertyName, String value) {
		Property<?> property = state.getBlock().getStateDefinition().getProperty(propertyName);
		return property != null && valueName(state, property).equals(value);
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	private BlockModels() {
	}
}

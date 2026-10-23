package io.github.joddabodscripts.j2b.bridge.resource;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import io.github.joddabodscripts.j2b.bridge.BridgedMods;
import io.github.joddabodscripts.j2b.bridge.block.BlockModels;
import io.github.joddabodscripts.j2b.bridge.block.BlockModels.ModelRef;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rewrites item model definitions that use a mod's own client-side model types or properties.
 *
 * <p>A vanilla client refuses the whole file when it meets an unknown type, and the item turns
 * into the missing-texture cube. Each unknown node is replaced by the closest plain model found
 * inside it, which keeps the item's normal look and drops only the special behaviour. A block item
 * with nothing plain inside shows its block's model.
 */
public final class ItemModelFixer {
	private static final Gson GSON = new Gson();

	public static void fix(ResourcePackBuilder builder) {
		List<String> paths = new ArrayList<>();
		builder.forEachResource((path, resource) -> {
			for (String namespace : BridgedMods.namespaces()) {
				if (path.startsWith("assets/" + namespace + "/items/") && path.endsWith(".json")) {
					paths.add(path);
				}
			}
		});

		for (String path : paths) {
			byte[] data = builder.getDataOrSource(path);
			if (data == null) {
				continue;
			}
			JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.has("model") && root.get("model").isJsonObject()) {
				JsonObject fixed = fixNode(root.getAsJsonObject("model"), blockModel(path));
				if (fixed != root.getAsJsonObject("model")) {
					root.add("model", fixed);
					builder.addData(path, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
				}
			}
		}
	}

	/**
	 * The model a block item's block shows, as a plain model node, for items drawn only by the
	 * mod's own code. Null for other items.
	 */
	private static @Nullable JsonObject blockModel(String path) {
		String[] parts = path.substring("assets/".length(), path.length() - ".json".length()).split("/items/", 2);
		Identifier id = Identifier.tryBuild(parts[0], parts[1]);
		if (id == null || !(BuiltInRegistries.ITEM.getValue(id) instanceof BlockItem item)) {
			return null;
		}
		List<List<ModelRef>> models = BlockModels.forState(item.getBlock().defaultBlockState());
		if (models.isEmpty()) {
			return null;
		}
		JsonObject plain = new JsonObject();
		plain.addProperty("type", "minecraft:model");
		plain.addProperty("model", models.getFirst().getFirst().model().toString());
		return plain;
	}

	/** Returns the node itself when it is fine, or a replacement when something inside was not. */
	private static JsonObject fixNode(JsonObject node, @Nullable JsonObject blockModel) {
		String type = node.has("type") ? node.get("type").getAsString() : "";
		if (!isVanilla(type)) {
			return replacementFor(node, blockModel);
		}
		boolean changed = false;
		JsonObject copy = node.deepCopy();
		switch (stripNamespace(type)) {
			case "condition" -> {
				if (!isVanilla(string(node, "property"))) {
					return fixNode(node.getAsJsonObject("on_false"), blockModel);
				}
				changed |= fixChild(copy, "on_true", blockModel);
				changed |= fixChild(copy, "on_false", blockModel);
			}
			case "select", "range_dispatch" -> {
				if (!isVanilla(string(node, "property"))) {
					JsonObject fallback = node.has("fallback") ? node.getAsJsonObject("fallback") : firstPlainModel(node);
					return fallback != null ? fixNode(fallback, blockModel) : empty();
				}
				changed |= fixChild(copy, "fallback", blockModel);
				String listName = stripNamespace(type).equals("select") ? "cases" : "entries";
				if (copy.has(listName)) {
					for (JsonElement entry : copy.getAsJsonArray(listName)) {
						changed |= fixChild(entry.getAsJsonObject(), "model", blockModel);
					}
				}
			}
			case "composite" -> {
				var models = copy.getAsJsonArray("models");
				for (int i = 0; i < models.size(); i++) {
					JsonObject child = models.get(i).getAsJsonObject();
					JsonObject fixed = fixNode(child, blockModel);
					if (fixed != child) {
						models.set(i, fixed);
						changed = true;
					}
				}
			}
			case "special" -> {
				JsonObject special = node.getAsJsonObject("model");
				if (special != null && !isVanilla(string(special, "type"))) {
					JsonObject plain = new JsonObject();
					plain.addProperty("type", "minecraft:model");
					plain.addProperty("model", string(node, "base"));
					return plain;
				}
			}
			default -> {
			}
		}
		return changed ? copy : node;
	}

	private static boolean fixChild(JsonObject parent, String key, @Nullable JsonObject blockModel) {
		if (!parent.has(key) || !parent.get(key).isJsonObject()) {
			return false;
		}
		JsonObject child = parent.getAsJsonObject(key);
		JsonObject fixed = fixNode(child, blockModel);
		if (fixed != child) {
			parent.add(key, fixed);
			return true;
		}
		return false;
	}

	private static JsonObject replacementFor(JsonObject unknown, @Nullable JsonObject blockModel) {
		JsonObject plain = firstPlainModel(unknown);
		if (plain != null) {
			return plain;
		}
		return blockModel != null ? blockModel : empty();
	}

	/** Depth-first search for a {@code minecraft:model} node. */
	private static @Nullable JsonObject firstPlainModel(JsonObject node) {
		if (stripNamespace(string(node, "type")).equals("model") && node.has("model") && node.get("model").isJsonPrimitive()) {
			return node;
		}
		for (Map.Entry<String, JsonElement> entry : node.entrySet()) {
			JsonElement value = entry.getValue();
			if (value.isJsonObject()) {
				JsonObject found = firstPlainModel(value.getAsJsonObject());
				if (found != null) {
					return found;
				}
			} else if (value.isJsonArray()) {
				for (JsonElement element : value.getAsJsonArray()) {
					if (element.isJsonObject()) {
						JsonObject found = firstPlainModel(element.getAsJsonObject());
						if (found != null) {
							return found;
						}
					}
				}
			}
		}
		return null;
	}

	private static JsonObject empty() {
		JsonObject empty = new JsonObject();
		empty.addProperty("type", "minecraft:empty");
		return empty;
	}

	private static String string(JsonObject node, String key) {
		return node.has(key) && node.get(key).isJsonPrimitive() ? node.get(key).getAsString() : "";
	}

	private static boolean isVanilla(String id) {
		return !id.contains(":") || id.startsWith("minecraft:");
	}

	private static String stripNamespace(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}

	private ItemModelFixer() {
	}
}

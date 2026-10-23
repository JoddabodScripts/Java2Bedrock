package io.github.joddabodscripts.j2b.bridge.resource;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import io.github.joddabodscripts.j2b.bridge.BridgedMods;
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
 * inside it, which keeps the item's normal look and drops only the special behaviour.
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
				JsonObject fixed = fixNode(root.getAsJsonObject("model"));
				if (fixed != root.getAsJsonObject("model")) {
					root.add("model", fixed);
					builder.addData(path, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
				}
			}
		}
	}

	/** Returns the node itself when it is fine, or a replacement when something inside was not. */
	private static JsonObject fixNode(JsonObject node) {
		String type = node.has("type") ? node.get("type").getAsString() : "";
		if (!isVanilla(type)) {
			return replacementFor(node);
		}
		boolean changed = false;
		JsonObject copy = node.deepCopy();
		switch (stripNamespace(type)) {
			case "condition" -> {
				if (!isVanilla(string(node, "property"))) {
					return fixNode(node.getAsJsonObject("on_false"));
				}
				changed |= fixChild(copy, "on_true");
				changed |= fixChild(copy, "on_false");
			}
			case "select", "range_dispatch" -> {
				if (!isVanilla(string(node, "property"))) {
					JsonObject fallback = node.has("fallback") ? node.getAsJsonObject("fallback") : firstPlainModel(node);
					return fallback != null ? fixNode(fallback) : empty();
				}
				changed |= fixChild(copy, "fallback");
				String listName = stripNamespace(type).equals("select") ? "cases" : "entries";
				if (copy.has(listName)) {
					for (JsonElement entry : copy.getAsJsonArray(listName)) {
						changed |= fixChild(entry.getAsJsonObject(), "model");
					}
				}
			}
			case "composite" -> {
				var models = copy.getAsJsonArray("models");
				for (int i = 0; i < models.size(); i++) {
					JsonObject child = models.get(i).getAsJsonObject();
					JsonObject fixed = fixNode(child);
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

	private static boolean fixChild(JsonObject parent, String key) {
		if (!parent.has(key) || !parent.get(key).isJsonObject()) {
			return false;
		}
		JsonObject child = parent.getAsJsonObject(key);
		JsonObject fixed = fixNode(child);
		if (fixed != child) {
			parent.add(key, fixed);
			return true;
		}
		return false;
	}

	private static JsonObject replacementFor(JsonObject unknown) {
		JsonObject plain = firstPlainModel(unknown);
		return plain != null ? plain : empty();
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

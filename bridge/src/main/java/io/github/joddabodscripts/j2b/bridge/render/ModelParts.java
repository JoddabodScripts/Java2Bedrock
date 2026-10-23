package io.github.joddabodscripts.j2b.bridge.render;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import io.github.joddabodscripts.j2b.bridge.J2bBridge;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import io.github.joddabodscripts.j2b.bridge.api.BlockEntityVisuals.ItemPart;
import io.github.joddabodscripts.j2b.bridge.client.ClientContext;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Turns the entity-style models a renderer draws (chests, shulker boxes, anything built from
 * {@link ModelPart}s) into item models a vanilla client can show on a display entity.
 *
 * <p>Each part with boxes becomes one generated item model, textured with whatever the renderer
 * asked for, and one display entity posed like the part. Animation, such as a chest lid opening,
 * only moves parts, so it comes through as pose changes. The models have to be in the resource
 * pack before players join, so {@link #prepare} runs every renderer once while the pack is built
 * to see which parts and textures it uses.
 */
public final class ModelParts {
	private static final Gson GSON = new Gson();
	/** Item model coordinates must stay within -16 to 32, which is 24 either side of the centre. */
	private static final float MAX_EXTENT = 23.9f;
	private static final float EPSILON = 1e-4f;

	private static final Map<ModelPart, String> GEOMETRY_KEYS = new IdentityHashMap<>();
	private static final Map<String, Generated> GENERATED = new LinkedHashMap<>();
	private static final Set<String> MISSING = new TreeSet<>();
	/** Textures other generated models use, which need to be on the block atlas too. */
	static final Set<String> OTHER_TEXTURES = new TreeSet<>();
	private static boolean preparing;

	/** A model written to the pack, and the scale its coordinates were shrunk by to fit. */
	private record Generated(Identifier itemModel, JsonObject model, String texture, float scale) {
	}

	static void prepare(Runnable renderEverything) {
		preparing = true;
		try {
			renderEverything.run();
		} finally {
			preparing = false;
		}
	}

	/** Called for every model a renderer submits. */
	static void submit(Model<?> model, Object state, PoseStack poseStack, RenderType renderType, @Nullable Object uvMapping, Consumer<ItemPart> out) {
		String texture = texture(renderType, uvMapping);
		if (texture == null) {
			return;
		}
		setupAnim(model, state);
		walk(model.root(), poseStack, texture, out);
	}

	@SuppressWarnings("unchecked")
	private static <S> void setupAnim(Model<S> model, Object state) {
		model.setupAnim((S) state);
	}

	private static void walk(ModelPart part, PoseStack poseStack, String texture, Consumer<ItemPart> out) {
		if (!part.visible) {
			return;
		}
		poseStack.pushPose();
		part.translateAndRotate(poseStack);
		if (!part.skipDraw && !part.isEmpty()) {
			emit(part, poseStack.last().pose(), texture, out);
		}
		for (ModelPart child : children(part).values()) {
			walk(child, poseStack, texture, out);
		}
		poseStack.popPose();
	}

	private static void emit(ModelPart part, Matrix4f pose, String texture, Consumer<ItemPart> out) {
		String key = GEOMETRY_KEYS.computeIfAbsent(part, ModelParts::geometryKey) + "|" + texture;
		Generated generated = GENERATED.get(key);
		if (generated == null) {
			if (!preparing) {
				if (MISSING.add(key)) {
					J2bBridge.LOGGER.debug("Model part with {} wasn't seen while building the pack, skipping it", texture);
				}
				return;
			}
			generated = generate(part, texture, GENERATED.size());
			GENERATED.put(key, generated);
		}
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.ITEM_MODEL, generated.itemModel());
		float unscale = 1 / generated.scale();
		out.accept(new ItemPart(stack, new Matrix4f(pose).scale(unscale), ItemDisplayContext.NONE));
	}

	private static Generated generate(ModelPart part, String texture, int index) {
		List<ModelPart.Cube> cubes = cubes(part);
		float extent = 0;
		for (ModelPart.Cube cube : cubes) {
			for (ModelPart.Polygon polygon : cube.polygons) {
				for (ModelPart.Vertex vertex : polygon.vertices()) {
					extent = Math.max(extent, Math.max(Math.abs(vertex.x()), Math.max(Math.abs(vertex.y()), Math.abs(vertex.z()))));
				}
			}
		}
		float scale = extent > MAX_EXTENT ? MAX_EXTENT / extent : 1;

		JsonArray elements = new JsonArray();
		for (ModelPart.Cube cube : cubes) {
			JsonObject element = element(cube, scale);
			if (element != null) {
				elements.add(element);
			}
		}
		JsonObject textures = new JsonObject();
		textures.addProperty("0", texture);
		textures.addProperty("particle", texture);
		JsonObject model = new JsonObject();
		model.add("textures", textures);
		model.add("elements", elements);
		return new Generated(J2bBridge.id("part/" + index), model, texture, scale);
	}

	/**
	 * One box. The faces keep the exact texture coordinates of the original polygons, worked out
	 * by matching each polygon corner to the corner the item model would put there.
	 */
	private static @Nullable JsonObject element(ModelPart.Cube cube, float scale) {
		if (cube.polygons.length == 0) {
			return null;
		}
		Vector3f min = new Vector3f(Float.MAX_VALUE);
		Vector3f max = new Vector3f(-Float.MAX_VALUE);
		for (ModelPart.Polygon polygon : cube.polygons) {
			for (ModelPart.Vertex vertex : polygon.vertices()) {
				min.min(new Vector3f(vertex.x(), vertex.y(), vertex.z()));
				max.max(new Vector3f(vertex.x(), vertex.y(), vertex.z()));
			}
		}
		JsonObject faces = new JsonObject();
		for (ModelPart.Polygon polygon : cube.polygons) {
			Face face = Face.of(polygon.normal());
			JsonObject json = face.json(vertices(polygon), min, max, "#0");
			if (json != null) {
				faces.add(face.name, json);
			}
		}
		JsonObject element = new JsonObject();
		element.add("from", vector(min, scale));
		element.add("to", vector(max, scale));
		element.add("faces", faces);
		return element;
	}

	private static float[][] vertices(ModelPart.Polygon polygon) {
		ModelPart.Vertex[] vertices = polygon.vertices();
		float[][] result = new float[vertices.length][];
		for (int i = 0; i < vertices.length; i++) {
			ModelPart.Vertex v = vertices[i];
			result[i] = new float[]{v.x(), v.y(), v.z(), v.u(), v.v()};
		}
		return result;
	}

	private static JsonArray vector(Vector3fc v, float scale) {
		JsonArray array = new JsonArray();
		array.add(v.x() * scale + 8);
		array.add(v.y() * scale + 8);
		array.add(v.z() * scale + 8);
		return array;
	}

	/** The corners of each item model face, in the order the model loader assigns texture corners. */
	enum Face {
		DOWN("down", "-x-y+z", "-x-y-z", "+x-y-z", "+x-y+z"),
		UP("up", "-x+y-z", "-x+y+z", "+x+y+z", "+x+y-z"),
		NORTH("north", "+x+y-z", "+x-y-z", "-x-y-z", "-x+y-z"),
		SOUTH("south", "-x+y+z", "-x-y+z", "+x-y+z", "+x+y+z"),
		WEST("west", "-x+y-z", "-x-y-z", "-x-y+z", "-x+y+z"),
		EAST("east", "+x+y+z", "+x-y+z", "+x-y-z", "+x+y-z");

		final String name;
		/** For each corner, whether x, y and z sit at the max side of the box. */
		final boolean[][] corners = new boolean[4][3];

		Face(String name, String... corners) {
			this.name = name;
			for (int i = 0; i < 4; i++) {
				for (int axis = 0; axis < 3; axis++) {
					this.corners[i][axis] = corners[i].charAt(axis * 2) == '+';
				}
			}
		}

		static Face of(Vector3fc normal) {
			float ax = Math.abs(normal.x()), ay = Math.abs(normal.y()), az = Math.abs(normal.z());
			if (ay >= ax && ay >= az) {
				return normal.y() < 0 ? DOWN : UP;
			}
			if (ax >= az) {
				return normal.x() < 0 ? WEST : EAST;
			}
			return normal.z() < 0 ? NORTH : SOUTH;
		}

		/** The axis the face is flat on. */
		int axis() {
			return switch (this) {
				case WEST, EAST -> 0;
				case DOWN, UP -> 1;
				case NORTH, SOUTH -> 2;
			};
		}

		/**
		 * The face of a box with corners {@code min} and {@code max}, drawn like the given polygon.
		 * Each vertex is x, y, z, then u and v from 0 to 1.
		 */
		@Nullable JsonObject json(float[][] vertices, Vector3fc min, Vector3fc max, String texture) {
			// Texture coordinates of the polygon vertex at each face corner, in 0-16 units.
			float[][] uv = new float[4][];
			for (int i = 0; i < 4; i++) {
				for (float[] vertex : vertices) {
					if (atCorner(vertex, this.corners[i], min, max)) {
						// Item models only take coordinates inside the texture. Entity models
						// sometimes reach past its edge, where nothing would be drawn anyway.
						uv[i] = new float[]{Math.clamp(vertex[3] * 16, 0, 16), Math.clamp(vertex[4] * 16, 0, 16)};
						break;
					}
				}
				if (uv[i] == null) {
					return null;
				}
			}
			// Corner i gets texture corner (i + shift) % 4: min/min, min/max, max/max, max/min.
			for (int shift = 0; shift < 4; shift++) {
				float[] minUv = new float[]{Float.NaN, Float.NaN};
				float[] maxUv = new float[]{Float.NaN, Float.NaN};
				boolean fits = true;
				for (int i = 0; i < 4 && fits; i++) {
					int k = (i + shift) % 4;
					fits = put(k <= 1 ? minUv : maxUv, 0, uv[i][0]) && put(k == 0 || k == 3 ? minUv : maxUv, 1, uv[i][1]);
				}
				if (fits) {
					JsonArray uvs = new JsonArray();
					uvs.add(minUv[0]);
					uvs.add(minUv[1]);
					uvs.add(maxUv[0]);
					uvs.add(maxUv[1]);
					JsonObject face = new JsonObject();
					face.add("uv", uvs);
					face.addProperty("texture", texture);
					if (shift != 0) {
						face.addProperty("rotation", shift * 90);
					}
					return face;
				}
			}
			return null;
		}

		private boolean atCorner(float[] position, boolean[] corner, Vector3fc min, Vector3fc max) {
			for (int axis = 0; axis < 3; axis++) {
				if (axis == axis()) {
					continue;
				}
				float expected = corner[axis] ? max.get(axis) : min.get(axis);
				if (Math.abs(position[axis] - expected) > EPSILON) {
					return false;
				}
			}
			return true;
		}

		private static boolean put(float[] target, int index, float value) {
			if (Float.isNaN(target[index])) {
				target[index] = value;
				return true;
			}
			return Math.abs(target[index] - value) < EPSILON;
		}
	}

	/** The texture a submit draws with, as a sprite id the item model can name. */
	private static @Nullable String texture(RenderType renderType, @Nullable Object uvMapping) {
		SpriteId sprite = uvMapping == null ? null : ClientContext.spriteId(uvMapping);
		if (sprite != null) {
			return sprite.texture().toString();
		}
		Identifier location = RenderTypes.texture(renderType);
		if (location == null) {
			return null;
		}
		String path = location.getPath();
		if (path.startsWith("textures/")) {
			path = path.substring("textures/".length());
		}
		if (path.endsWith(".png")) {
			path = path.substring(0, path.length() - ".png".length());
		}
		return location.withPath(path).toString();
	}

	/** Adds the generated models, and puts their textures on the block atlas so items can use them. */
	public static void write(ResourcePackBuilder builder) {
		addVariantTextures();
		Set<String> textures = new TreeSet<>(OTHER_TEXTURES);
		for (Generated generated : GENERATED.values()) {
			Identifier id = generated.itemModel();
			builder.addData("assets/" + id.getNamespace() + "/models/" + id.getPath() + ".json", json(generated.model()));
			JsonObject itemModel = new JsonObject();
			itemModel.addProperty("type", "minecraft:model");
			itemModel.addProperty("model", id.toString());
			JsonObject item = new JsonObject();
			item.add("model", itemModel);
			builder.addData("assets/" + id.getNamespace() + "/items/" + id.getPath() + ".json", json(item));
			textures.add(generated.texture());
		}
		String atlasPath = "assets/minecraft/atlases/blocks.json";
		JsonArray sources = new JsonArray();
		for (String texture : textures) {
			JsonObject source = new JsonObject();
			source.addProperty("type", "minecraft:single");
			source.addProperty("resource", texture);
			sources.add(source);
		}
		JsonObject atlas = new JsonObject();
		atlas.add("sources", sources);
		// Polymer merges atlas files, so this only adds to whatever the mods' own atlases list.
		builder.addData(atlasPath, json(atlas));
		J2bBridge.LOGGER.info("Generated {} item models from the models mods' renderers draw, using {} textures", GENERATED.size(), textures.size());
	}

	/**
	 * Mobs often come in variants that swap the texture and keep the model, and the pack is built
	 * from one entity of each kind. So every part also gets a copy for each other texture of the
	 * same size in the same folder, such as {@code entity/rat/black.png} and {@code entity/rat/brown.png}.
	 */
	private static void addVariantTextures() {
		Map<String, List<String>> siblings = new HashMap<>();
		for (Map.Entry<String, Generated> entry : List.copyOf(GENERATED.entrySet())) {
			String key = entry.getKey();
			String geometry = key.substring(0, key.lastIndexOf('|'));
			Generated generated = entry.getValue();
			for (String sibling : siblings.computeIfAbsent(generated.texture(), ModelParts::sameSizeSiblings)) {
				String siblingKey = geometry + "|" + sibling;
				if (GENERATED.containsKey(siblingKey)) {
					continue;
				}
				JsonObject model = generated.model().deepCopy();
				model.getAsJsonObject("textures").addProperty("0", sibling);
				model.getAsJsonObject("textures").addProperty("particle", sibling);
				GENERATED.put(siblingKey, new Generated(J2bBridge.id("part/" + GENERATED.size()), model, sibling, generated.scale()));
			}
		}
	}

	private static List<String> sameSizeSiblings(String texture) {
		Identifier id = Identifier.tryParse(texture);
		int slash = id == null ? -1 : id.getPath().lastIndexOf('/');
		// Only textures in a folder of their own; a mod's top entity folder holds every mob.
		if (id == null || slash <= "entity".length()) {
			return List.of();
		}
		String folder = "assets/" + id.getNamespace() + "/textures/" + id.getPath().substring(0, slash);
		List<String> result = new ArrayList<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			for (Path root : mod.getRootPaths()) {
				Path dir = root.resolve(folder);
				Path own = root.resolve("assets/" + id.getNamespace() + "/textures/" + id.getPath() + ".png");
				if (!Files.isRegularFile(own)) {
					continue;
				}
				long size = pngSize(own);
				try (Stream<Path> files = Files.list(dir)) {
					files.filter(file -> file.toString().endsWith(".png") && !file.equals(own) && pngSize(file) == size)
							.forEach(file -> {
								String name = file.getFileName().toString();
								result.add(id.withPath(id.getPath().substring(0, slash + 1) + name.substring(0, name.length() - 4)).toString());
							});
				} catch (IOException e) {
					J2bBridge.LOGGER.debug("Could not list {}", dir, e);
				}
			}
		}
		return result;
	}

	/** Width and height from a PNG header, packed in one number, or -1 if unreadable. */
	private static long pngSize(Path file) {
		try (var in = Files.newInputStream(file)) {
			byte[] header = in.readNBytes(24);
			if (header.length < 24) {
				return -1;
			}
			var buffer = java.nio.ByteBuffer.wrap(header);
			return ((long) buffer.getInt(16) << 32) | (buffer.getInt(20) & 0xffffffffL);
		} catch (IOException e) {
			return -1;
		}
	}

	private static byte[] json(JsonObject object) {
		return GSON.toJson(object).getBytes(StandardCharsets.UTF_8);
	}

	private static String geometryKey(ModelPart part) {
		StringBuilder key = new StringBuilder();
		for (ModelPart.Cube cube : cubes(part)) {
			for (ModelPart.Polygon polygon : cube.polygons) {
				for (ModelPart.Vertex v : polygon.vertices()) {
					key.append(v.x()).append(',').append(v.y()).append(',').append(v.z()).append(',').append(v.u()).append(',').append(v.v()).append(';');
				}
			}
			key.append('/');
		}
		return Integer.toHexString(key.toString().hashCode()) + Integer.toHexString(key.length());
	}

	private static final Field CUBES = field("cubes");
	private static final Field CHILDREN = field("children");

	@SuppressWarnings("unchecked")
	private static List<ModelPart.Cube> cubes(ModelPart part) {
		try {
			return (List<ModelPart.Cube>) CUBES.get(part);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, ModelPart> children(ModelPart part) {
		try {
			return (Map<String, ModelPart>) CHILDREN.get(part);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Field field(String name) {
		try {
			Field field = ModelPart.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (NoSuchFieldException e) {
			throw new IllegalStateException(e);
		}
	}

	private ModelParts() {
	}
}

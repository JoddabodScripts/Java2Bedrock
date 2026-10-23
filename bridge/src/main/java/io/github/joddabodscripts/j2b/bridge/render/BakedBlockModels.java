package io.github.joddabodscripts.j2b.bridge.render;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import io.github.joddabodscripts.j2b.bridge.Assets;
import io.github.joddabodscripts.j2b.bridge.BridgedMods;
import io.github.joddabodscripts.j2b.bridge.J2bBridge;
import io.github.joddabodscripts.j2b.bridge.client.ClientClasses;
import io.github.joddabodscripts.j2b.bridge.client.ClientContext;
import io.github.joddabodscripts.j2b.bridge.client.ServerBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.fabricmc.fabric.impl.client.model.loading.CustomUnbakedBlockStateModelRegistry;
import net.fabricmc.fabric.impl.client.model.loading.UnbakedModelJsonDeserializer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelDiscovery;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.resources.model.cuboid.MissingCuboidModel;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Block models a mod builds in its own client code, through a Fabric model loader
 * ({@code "fabric:type"} in a blockstate or model file). There is no model file a vanilla client
 * could read for these, so the server bakes them the way the client would, with the mod's own
 * loader, and writes the faces it gets back out as a plain block model.
 *
 * <p>Each face becomes a flat element. Faces at an angle other than a right angle come out
 * straightened, since block model elements can't lean that way without losing their texture
 * placement. The model is baked once per blockstate entry, so anything the mod changes while a
 * block sits in the world (a dyed colour, what fills a tank) isn't shown.
 */
public final class BakedBlockModels {
	private static final Gson GSON = new Gson();
	private static final float EPSILON = 1e-4f;
	private static final Map<String, Request> REQUESTS = new LinkedHashMap<>();

	private record Request(Identifier model, BlockState state, JsonElement entry) {
	}

	/** Whether a blockstate entry, or the model it names, needs a mod's model loader. */
	public static boolean needsBaking(JsonObject entry) {
		if (!ClientClasses.available()) {
			return false;
		}
		if (entry.has("fabric:type")) {
			return true;
		}
		Identifier model = entry.has("model") ? Identifier.tryParse(entry.get("model").getAsString()) : null;
		return model != null && modelFile(model).map(json -> json.has("fabric:type")).orElse(false);
	}

	/** The model the pack will have for this blockstate entry, once {@link #write} has baked it. */
	public static synchronized Identifier request(BlockState state, JsonElement entry) {
		String key = BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "|" + entry;
		return REQUESTS.computeIfAbsent(key, k -> new Request(J2bBridge.id("baked/" + REQUESTS.size()), state, entry)).model();
	}

	static synchronized void write(ResourcePackBuilder builder) {
		if (REQUESTS.isEmpty()) {
			return;
		}
		ClientContext.get();
		Map<BlockState, BlockStateModel.UnbakedRoot> roots = new HashMap<>();
		for (Request request : REQUESTS.values()) {
			CustomUnbakedBlockStateModelRegistry.MODEL_CODEC.parse(JsonOps.INSTANCE, request.entry())
					.ifSuccess(unbaked -> roots.put(request.state(), unbaked.asRoot()))
					.ifError(error -> J2bBridge.LOGGER.warn("Can't read the model of {}: {}", request.state(), error.message()));
		}

		ModelDiscovery discovery = new ModelDiscovery(new ModelFiles(), MissingCuboidModel.missingModel());
		roots.values().forEach(discovery::addRoot);
		// A client loads every model file, and mod loaders often ask for models no blockstate names.
		for (Identifier model : modModels()) {
			discovery.addRoot(resolver -> resolver.markDependency(model));
		}
		ModelBakery bakery = new ModelBakery(ClientContext.models(), ClientContext::sprite, null, roots, Map.of(), discovery.resolve(), discovery.missingModel());
		Map<BlockState, BlockStateModel> baked = bakery.bakeModels(ClientContext.materials(), Runnable::run).join().blockStateModels();

		int written = 0;
		for (Request request : REQUESTS.values()) {
			BlockStateModel model = baked.get(request.state());
			JsonObject json = model == null ? null : toJson(model, request.state());
			if (json == null) {
				J2bBridge.LOGGER.warn("Baking the model of {} on the server gave no faces, it stays invisible", request.state());
				continue;
			}
			Identifier id = request.model();
			builder.addData("assets/" + id.getNamespace() + "/models/" + id.getPath() + ".json", GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
			written++;
		}
		J2bBridge.LOGGER.info("Baked {} block models that mods build with their own model loaders", written);
	}

	/** One face as the baked model has it: four corners of x, y, z, u, v in atlas units. */
	private record Quad(float[][] vertices, Direction direction, int tintIndex) {
		static Quad of(QuadView quad) {
			float[][] vertices = new float[4][];
			for (int i = 0; i < 4; i++) {
				vertices[i] = new float[]{quad.x(i), quad.y(i), quad.z(i), quad.u(i), quad.v(i)};
			}
			Direction direction = quad.nominalFace() != null ? quad.nominalFace() : quad.lightFace();
			return new Quad(vertices, direction, quad.tintIndex());
		}
	}

	private static @Nullable JsonObject toJson(BlockStateModel model, BlockState state) {
		List<Quad> quads;
		try {
			quads = quads(model, state);
		} catch (Throwable e) {
			J2bBridge.LOGGER.warn("The model of {} failed to build its faces on the server: {}", state, e.toString());
			J2bBridge.LOGGER.debug("Stack trace", e);
			return null;
		}
		Map<String, String> textures = new LinkedHashMap<>();
		JsonArray elements = new JsonArray();
		for (Quad quad : quads) {
			float u = 0;
			for (float[] vertex : quad.vertices()) {
				u += vertex[3] / 4;
			}
			SpriteId sprite = ClientContext.spriteAt(u);
			if (sprite == null) {
				continue;
			}
			// Back to coordinates on the texture itself, from its square of the made-up atlas.
			float offset = (float) Math.floor(u);
			for (float[] vertex : quad.vertices()) {
				vertex[3] -= offset;
			}
			String texture = textures.computeIfAbsent(sprite.texture().toString(), t -> "t" + textures.size());
			ModelParts.OTHER_TEXTURES.add(sprite.texture().toString());
			JsonObject element = element(quad, "#" + texture);
			if (element != null) {
				elements.add(element);
			}
		}
		if (elements.isEmpty()) {
			return null;
		}
		JsonObject textureJson = new JsonObject();
		textures.forEach((texture, key) -> textureJson.addProperty(key, texture));
		textureJson.addProperty("particle", textures.keySet().iterator().next());
		JsonObject json = new JsonObject();
		// For the usual block item angles in hand and in the inventory.
		json.addProperty("parent", "minecraft:block/block");
		json.add("textures", textureJson);
		json.add("elements", elements);
		return json;
	}

	/**
	 * The faces a model draws, as Fabric's renderer asks for them on a client. Models built for
	 * that renderer only hand over their faces this way.
	 */
	private static List<Quad> quads(BlockStateModel model, BlockState state) {
		List<Quad> quads = new ArrayList<>();
		QuadEmitter emitter = IndigoRenderer.INSTANCE.quadEmitter(quad -> quads.add(Quad.of(quad)));
		Predicate<Direction> cullNothing = direction -> false;
		((ServerBlockStateModel) model).emitQuads(emitter, BlockAndTintGetter.EMPTY, BlockPos.ZERO, state, RandomSource.create(42), cullNothing);
		return quads;
	}

	/** One face as a flat element, with the texture placed the way the baked face had it. */
	private static @Nullable JsonObject element(Quad quad, String texture) {
		float[][] vertices = quad.vertices();
		Vector3f min = new Vector3f(Float.MAX_VALUE);
		Vector3f max = new Vector3f(-Float.MAX_VALUE);
		for (float[] vertex : vertices) {
			for (int axis = 0; axis < 3; axis++) {
				vertex[axis] *= 16;
			}
			Vector3f position = new Vector3f(vertex[0], vertex[1], vertex[2]);
			min.min(position);
			max.max(position);
		}
		ModelParts.Face face = ModelParts.Face.valueOf(quad.direction().name());
		// A face at an angle is laid flat at its middle.
		int axis = face.axis();
		if (max.get(axis) - min.get(axis) > EPSILON) {
			float middle = (min.get(axis) + max.get(axis)) / 2;
			min.setComponent(axis, middle);
			max.setComponent(axis, middle);
			for (float[] vertex : vertices) {
				vertex[axis] = middle;
			}
		}
		JsonObject faceJson = face.json(vertices, min, max, texture);
		if (faceJson == null) {
			return null;
		}
		if (quad.tintIndex() != -1) {
			faceJson.addProperty("tintindex", quad.tintIndex());
		}
		JsonObject faces = new JsonObject();
		faces.add(quad.direction().getSerializedName(), faceJson);
		JsonObject element = new JsonObject();
		element.add("from", vector(min));
		element.add("to", vector(max));
		element.add("faces", faces);
		return element;
	}

	private static JsonArray vector(Vector3f v) {
		JsonArray array = new JsonArray();
		array.add(Math.clamp(v.x, -16, 32));
		array.add(Math.clamp(v.y, -16, 32));
		array.add(Math.clamp(v.z, -16, 32));
		return array;
	}

	/** Every model file in the bridged mods. */
	private static List<Identifier> modModels() {
		List<Identifier> models = new ArrayList<>();
		for (String namespace : BridgedMods.namespaces()) {
			BridgedMods.container(namespace).ifPresent(mod -> {
				for (Path root : mod.getRootPaths()) {
					Path dir = root.resolve("assets/" + namespace + "/models");
					if (!Files.isDirectory(dir)) {
						continue;
					}
					try (Stream<Path> files = Files.walk(dir)) {
						files.filter(file -> file.toString().endsWith(".json")).forEach(file -> {
							String path = dir.relativize(file).toString().replace('\\', '/');
							Identifier id = Identifier.tryBuild(namespace, path.substring(0, path.length() - ".json".length()));
							if (id != null) {
								models.add(id);
							}
						});
					} catch (IOException e) {
						J2bBridge.LOGGER.debug("Could not list {}", dir, e);
					}
				}
			});
		}
		return models;
	}

	private static Optional<JsonObject> modelFile(Identifier id) {
		return Assets.json("assets/" + id.getNamespace() + "/models/" + id.getPath() + ".json")
				.filter(JsonElement::isJsonObject)
				.map(JsonElement::getAsJsonObject);
	}

	/**
	 * Reads model files like the client does with Fabric's model loading module, which hooks the
	 * game's own reader. That hook isn't in place on a server, so this adds it to a copy.
	 */
	private static final Gson MODEL_GSON = modelGson();

	private static Gson modelGson() {
		try {
			Field field = CuboidModel.class.getDeclaredField("GSON");
			field.setAccessible(true);
			return ((Gson) field.get(null)).newBuilder()
					.registerTypeAdapter(UnbakedModel.class, new UnbakedModelJsonDeserializer())
					.create();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The mods' model files, read when the model discovery asks for one. */
	private static final class ModelFiles extends AbstractMap<Identifier, UnbakedModel> {
		private final Map<Identifier, Optional<UnbakedModel>> loaded = new HashMap<>();

		@Override
		public @Nullable UnbakedModel get(Object key) {
			if (!(key instanceof Identifier id)) {
				return null;
			}
			return this.loaded.computeIfAbsent(id, k -> modelFile(id).flatMap(json -> {
				try {
					return Optional.of(MODEL_GSON.fromJson(json, UnbakedModel.class));
				} catch (RuntimeException e) {
					J2bBridge.LOGGER.warn("Can't read model {}: {}", id, e.toString());
					return Optional.empty();
				}
			})).orElse(null);
		}

		@Override
		public Set<Entry<Identifier, UnbakedModel>> entrySet() {
			return Set.of();
		}
	}

	private BakedBlockModels() {
	}
}

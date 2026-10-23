package io.github.joddabodscripts.j2b.bridge.render;

import com.mojang.blaze3d.vertex.PoseStack;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import io.github.joddabodscripts.j2b.bridge.BridgedMods;
import io.github.joddabodscripts.j2b.bridge.J2bBridge;
import io.github.joddabodscripts.j2b.bridge.api.BlockEntityVisuals.ItemPart;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import io.github.joddabodscripts.j2b.bridge.client.ClientClasses;
import io.github.joddabodscripts.j2b.bridge.client.ClientContext;
import io.github.joddabodscripts.j2b.bridge.client.ClientRegistrations;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Runs a mod's own block entity renderers on the server.
 *
 * <p>The renderers ship in the mod's jar even on a dedicated server. {@link ClientClasses} puts the
 * game's client classes next to them, and the renderer draws into a collector that keeps what it
 * submits: items, with their exact pose, and entity-style models such as a chest, split into
 * parts (see {@link ModelParts}). Each becomes a display entity. A renderer that needs something
 * the server can't provide (fonts, the GPU, a model layer only its client setup registers) fails,
 * gets logged once, and is left out.
 */
public final class ModRenderers {
	private static final String RENDERER = "net/minecraft/client/renderer/blockentity/BlockEntityRenderer";
	/** Takes every kind of submit, keeps models and items, and ignores the rest. */
	private static final class Collector {
		// In its own class so nothing touches client classes until they're known to be there.
		static final SubmitNodeCollector INSTANCE = (SubmitNodeCollector) Proxy.newProxyInstance(
				ModRenderers.class.getClassLoader(), new Class<?>[]{SubmitNodeCollector.class}, ModRenderers::collect);
	}

	private static final int MAX_STATES_PER_BLOCK = 256;

	private static @Nullable Map<Class<?>, BlockEntityRenderer<?, ?>> renderers;
	private static final Map<Class<?>, Optional<BlockEntityRenderer<?, ?>>> BY_CLASS = new HashMap<>();
	private static final Set<BlockEntityRenderer<?, ?>> BROKEN = new HashSet<>();

	private static @Nullable Object collect(Object proxy, Method method, Object @Nullable [] args) throws Throwable {
		if (method.getName().equals("order")) {
			return proxy;
		}
		if (method.getName().equals("submitModel") && !method.isDefault() && args != null) {
			// (model, state, poseStack, renderType, light, overlay, tint, uvMapping, outline)
			RenderCapture.model((Model<?>) args[0], args[1], (PoseStack) args[2], (RenderType) args[3], args[7]);
			return null;
		}
		if (method.isDefault()) {
			return InvocationHandler.invokeDefault(proxy, method, args);
		}
		return switch (method.getName()) {
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == args[0];
			case "toString" -> "j2b submit collector";
			default -> null;
		};
	}

	/**
	 * Runs every renderer once for each block state that has it, and puts the models they draw in
	 * the resource pack.
	 */
	public static void addModels(ResourcePackBuilder builder) {
		if (!ClientClasses.available()) {
			return;
		}
		prepare();
		ModelParts.prepare(() -> ModEntityRenderers.prepare(J2bBridge.server() == null ? null : J2bBridge.server().overworld()));
		try {
			BakedBlockModels.write(builder);
		} catch (Throwable e) {
			J2bBridge.LOGGER.warn("Couldn't bake the blocks that use a mod's model loader, they stay invisible", e);
		}
		ModelParts.write(builder);
	}

	private static void prepare() {
		ModelParts.prepare(() -> {
			for (Block block : BuiltInRegistries.BLOCK) {
				if (!(block instanceof EntityBlock entityBlock) || !BridgedMods.isBridged(BuiltInRegistries.BLOCK.getKey(block).getNamespace())) {
					continue;
				}
				for (BlockState state : block.getStateDefinition().getPossibleStates().stream().limit(MAX_STATES_PER_BLOCK).toList()) {
					try {
						BlockEntity blockEntity = entityBlock.newBlockEntity(BlockPos.ZERO, state);
						if (blockEntity != null) {
							render(blockEntity, part -> {
							});
						}
					} catch (Throwable e) {
						J2bBridge.LOGGER.debug("Could not make a block entity for {}", state, e);
					}
				}
			}
		});
	}

	/** Runs the renderer for this block entity, if its mod has one the bridge can use. */
	public static synchronized boolean render(BlockEntity blockEntity, Consumer<ItemPart> out) {
		if (!ClientClasses.available()) {
			return false;
		}
		BlockEntityRenderer<?, ?> renderer = find(blockEntity);
		if (renderer == null || BROKEN.contains(renderer)) {
			return false;
		}
		try {
			RenderCapture.run(out, () -> run(renderer, blockEntity));
			return true;
		} catch (Throwable e) {
			BROKEN.add(renderer);
			J2bBridge.LOGGER.warn("Can't run {} on the server, its block entities won't show what it draws: {}",
					renderer.getClass().getName(), e.toString());
			return false;
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends BlockEntity, S extends BlockEntityRenderState> void run(BlockEntityRenderer<T, S> renderer, BlockEntity blockEntity) {
		T typed = (T) blockEntity;
		S state = renderer.createRenderState();
		Vec3 camera = Vec3.atCenterOf(blockEntity.getBlockPos()).add(0, 2, 0);
		renderer.extractRenderState(typed, state, 1, camera, null);
		CameraRenderState cameraState = new CameraRenderState();
		cameraState.pos = camera;
		cameraState.blockPos = blockEntity.getBlockPos().above(2);
		renderer.submit(state, new PoseStack(), Collector.INSTANCE, cameraState);
	}

	static SubmitNodeCollector collector() {
		return Collector.INSTANCE;
	}

	private static final Map<BlockEntityType<?>, Optional<BlockEntityRenderer<?, ?>>> BY_TYPE = new HashMap<>();

	/** The renderer the mod registered for this type in its client setup, built once. */
	private static @Nullable BlockEntityRenderer<?, ?> registered(BlockEntityType<?> type) {
		ClientContext.get();
		return BY_TYPE.computeIfAbsent(type, t -> {
			BlockEntityRendererProvider<?, ?> provider = ClientRegistrations.blockEntityRenderers().get(t);
			if (provider == null) {
				return Optional.empty();
			}
			try {
				return Optional.of(provider.create(ClientContext.get()));
			} catch (Throwable e) {
				J2bBridge.LOGGER.info("Skipping renderer for {}: {}", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(t), e.toString());
				return Optional.empty();
			}
		}).orElse(null);
	}

	private static @Nullable BlockEntityRenderer<?, ?> find(BlockEntity blockEntity) {
		BlockEntityRenderer<?, ?> registered = registered(blockEntity.getType());
		return registered != null ? registered : find(blockEntity.getClass());
	}

	/** Falls back to looking through the mod's classes, for renderers its setup didn't reach. */
	private static @Nullable BlockEntityRenderer<?, ?> find(Class<?> blockEntityClass) {
		return BY_CLASS.computeIfAbsent(blockEntityClass, type -> {
			Map<Class<?>, BlockEntityRenderer<?, ?>> all = all();
			for (Class<?> c = type; c != null && c != BlockEntity.class; c = c.getSuperclass()) {
				if (all.containsKey(c)) {
					return Optional.of(all.get(c));
				}
			}
			return Optional.empty();
		}).orElse(null);
	}

	private static synchronized Map<Class<?>, BlockEntityRenderer<?, ?>> all() {
		if (renderers == null) {
			renderers = new HashMap<>();
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				if (BridgedMods.isBridged(mod.getMetadata().getId())) {
					discover(mod);
				}
			}
			J2bBridge.LOGGER.info("Found {} block entity renderers to run on the server", renderers.size());
		}
		return renderers;
	}

	/** Finds every concrete class in the mod that implements BlockEntityRenderer, and builds it. */
	private static void discover(ModContainer mod) {
		Map<String, ClassInfo> classes = new HashMap<>();
		for (Path root : mod.getRootPaths()) {
			try (Stream<Path> files = Files.walk(root)) {
				files.filter(path -> path.toString().endsWith(".class")).forEach(path -> {
					try {
						ClassReader reader = new ClassReader(Files.readAllBytes(path));
						classes.put(reader.getClassName(), new ClassInfo(reader.getSuperName(), List.of(reader.getInterfaces()), reader.getAccess()));
					} catch (IOException | RuntimeException e) {
						J2bBridge.LOGGER.debug("Skipping {}", path, e);
					}
				});
			} catch (IOException e) {
				J2bBridge.LOGGER.debug("Could not scan {}", root, e);
			}
		}

		for (Map.Entry<String, ClassInfo> entry : classes.entrySet()) {
			ClassInfo info = entry.getValue();
			if ((info.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0 || !isRenderer(entry.getKey(), classes)) {
				continue;
			}
			String name = entry.getKey().replace('/', '.');
			try {
				Class<?> rendererClass = Class.forName(name, false, ModRenderers.class.getClassLoader());
				Class<?> blockEntityClass = blockEntityType(rendererClass);
				BlockEntityRenderer<?, ?> renderer = construct(rendererClass);
				if (blockEntityClass != null && renderer != null) {
					renderers.put(blockEntityClass, renderer);
				}
			} catch (Throwable e) {
				J2bBridge.LOGGER.info("Skipping renderer {}: {}", name, e.toString());
			}
		}
	}

	private static boolean isRenderer(String name, Map<String, ClassInfo> classes) {
		for (String current = name; current != null; ) {
			ClassInfo info = classes.get(current);
			if (info == null) {
				return false;
			}
			if (info.interfaces.contains(RENDERER)) {
				return true;
			}
			current = info.superName;
		}
		return false;
	}

	private static @Nullable BlockEntityRenderer<?, ?> construct(Class<?> rendererClass) throws ReflectiveOperationException {
		for (Constructor<?> constructor : rendererClass.getDeclaredConstructors()) {
			Class<?>[] params = constructor.getParameterTypes();
			constructor.setAccessible(true);
			if (params.length == 1 && params[0] == BlockEntityRendererProvider.Context.class) {
				return (BlockEntityRenderer<?, ?>) constructor.newInstance(ClientContext.get());
			}
			if (params.length == 0) {
				return (BlockEntityRenderer<?, ?>) constructor.newInstance();
			}
		}
		return null;
	}

	/** The block entity class a renderer is declared for, following generics up the hierarchy. */
	private static @Nullable Class<?> blockEntityType(Class<?> rendererClass) {
		Map<TypeVariable<?>, Type> bindings = new HashMap<>();
		for (Class<?> c = rendererClass; c != null; c = c.getSuperclass()) {
			for (Type type : c.getGenericInterfaces()) {
				if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == BlockEntityRenderer.class) {
					return resolve(parameterized.getActualTypeArguments()[0], bindings);
				}
			}
			if (c.getGenericSuperclass() instanceof ParameterizedType parent && parent.getRawType() instanceof Class<?> parentClass) {
				TypeVariable<?>[] variables = parentClass.getTypeParameters();
				Type[] arguments = parent.getActualTypeArguments();
				for (int i = 0; i < variables.length; i++) {
					bindings.put(variables[i], arguments[i] instanceof TypeVariable<?> v && bindings.containsKey(v) ? bindings.get(v) : arguments[i]);
				}
			}
		}
		return null;
	}

	private static @Nullable Class<?> resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
		if (type instanceof Class<?> c) {
			return c;
		}
		if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> c) {
			return c;
		}
		if (type instanceof TypeVariable<?> variable) {
			Type bound = bindings.get(variable);
			if (bound != null && bound != variable) {
				return resolve(bound, bindings);
			}
			// A renderer generic over its block entity covers everything within the bound.
			return resolve(variable.getBounds()[0], bindings);
		}
		return null;
	}

	private record ClassInfo(String superName, List<String> interfaces, int access) {
	}

	private ModRenderers() {
	}
}

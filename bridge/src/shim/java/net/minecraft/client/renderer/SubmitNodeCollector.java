package net.minecraft.client.renderer;

/** Server-side stand-in, written for the bridge. See {@link OrderedSubmitNodeCollector}. */
public interface SubmitNodeCollector extends OrderedSubmitNodeCollector {
	OrderedSubmitNodeCollector order(int order);
}

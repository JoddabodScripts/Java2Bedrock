package net.minecraft.client.renderer;

/**
 * Server-side stand-in, written for the bridge. Renderers hand it to the item states they draw,
 * which is all the bridge needs. A renderer that calls a draw method here directly fails to link
 * and the bridge stops using it.
 */
public interface OrderedSubmitNodeCollector {
}

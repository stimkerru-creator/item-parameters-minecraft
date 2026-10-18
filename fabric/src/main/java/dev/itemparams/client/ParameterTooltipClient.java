package dev.itemparams.client;

import dev.itemparams.core.Numeric;
import dev.itemparams.fabric.ParameterNbtStorage;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.text.Text;

/** Display ONLY locally attached item data. Does not allow edits or trust client numbers. */
public final class ParameterTooltipClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ItemTooltipCallback.EVENT.register((stack, context, lines) -> {
            if (stack.isEmpty() || !stack.hasNbt()
                    || !stack.getNbt().contains(ParameterNbtStorage.NBT_KEY)) return;
            try {
                var saved = ParameterNbtStorage.storedRaw(stack.getNbt());
                int count = 0;
                for (var entry : saved.entrySet()) {
                    if (count++ >= 30) {
                        lines.add(Text.literal("... +" + (saved.size() - 30) + " more parameters"));
                        break;
                    }
                    lines.add(Text.literal(entry.getKey() + ": " + Numeric.decode(entry.getValue()).toPlainString()));
                }
            } catch (IllegalArgumentException invalid) {
                // Malformed NBT should not crash the client tooltip renderer.
            }
        });
    }
}

package dev.itemparams.fabric.machine;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screen.ingame.HandledScreens;

public final class ProcessorClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        HandledScreens.register(ProcessorRegistry.SCREEN_HANDLER_TYPE, ProcessorScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(ProcessorRegistry.RECIPE_STATE_PACKET,
                (client, handler, buf, responseSender) -> {
                    int syncId = buf.readVarInt();
                    String recipeId = buf.readString(256);
                    int recipeIndex = buf.readVarInt();
                    int recipeCount = buf.readVarInt();
                    int roleCount = buf.readVarInt();
                    if (roleCount < 0 || roleCount > ProcessorBlockEntity.MAX_INPUTS) return;
                    java.util.ArrayList<String> roles = new java.util.ArrayList<>(roleCount);
                    java.util.ArrayList<String> inputItemIds = new java.util.ArrayList<>(roleCount);
                    for (int i = 0; i < roleCount; i++) {
                        roles.add(buf.readString(256));
                        inputItemIds.add(buf.readString(256));
                    }
                    String outputItemId = buf.readString(256);
                    client.execute(() -> {
                        if (client.player != null && client.player.currentScreenHandler instanceof ProcessorScreenHandler current
                                && current.syncId == syncId) {
                            current.updateRecipeInfo(recipeId, recipeIndex, recipeCount, roles, inputItemIds, outputItemId);
                        }
                    });
                });
    }
}

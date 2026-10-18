package dev.itemparams.fabric.machine;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/** Minimal test GUI: users choose only server-preconfigured recipe IDs; formulas and values are never editable here. */
public final class ProcessorScreen extends HandledScreen<ProcessorScreenHandler> {
    public ProcessorScreen(ProcessorScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        backgroundWidth = 230;
        backgroundHeight = 202;
        playerInventoryTitleY = 105;
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        drawMouseoverTooltip(context, mouseX, mouseY);
    }

    @Override protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        context.fill(x, y, x + backgroundWidth, y + backgroundHeight, 0xFFC6C6C6);
        context.fill(x, y, x + backgroundWidth, y + 2, 0xFFFFFFFF);
        context.fill(x, y, x + 2, y + backgroundHeight, 0xFFFFFFFF);
        context.fill(x + backgroundWidth - 2, y, x + backgroundWidth, y + backgroundHeight, 0xFF555555);
        context.fill(x, y + backgroundHeight - 2, x + backgroundWidth, y + backgroundHeight, 0xFF555555);

        for (int input = 0; input < ProcessorBlockEntity.MAX_INPUTS; input++)
            drawSlot(context, x + 10 + input * 22, y + 38);
        drawSlot(context, x + 184, y + 38);
        for (int row = 0; row < 3; row++) for (int column = 0; column < 9; column++)
            drawSlot(context, x + 8 + column * 18, y + 122 + row * 18);
        for (int column = 0; column < 9; column++) drawSlot(context, x + 8 + column * 18, y + 180);

        drawButton(context, x + 8, y + 66, x + 29, y + 84, "<", handler.canChangeRecipe());
        drawButton(context, x + 160, y + 66, x + 198, y + 84, Text.translatable("gui.itemparams_demo.reset").getString(), handler.canReconfigure());
        drawButton(context, x + 201, y + 66, x + 222, y + 84, ">", handler.canChangeRecipe());
        context.fill(x + 108, y + 39, x + 153, y + 47, 0xFF555555);
        int width = handler.progress() * 45 / ProcessorBlockEntity.OPERATION_TICKS;
        context.fill(x + 108, y + 39, x + 108 + width, y + 47, 0xFF5E9E54);
    }

    @Override protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        super.drawForeground(context, mouseX, mouseY);
        context.drawText(textRenderer, Text.translatable("gui.itemparams_demo.inputs"), 10, 23, 0x404040, false);
        context.drawText(textRenderer, Text.translatable("gui.itemparams_demo.output"), 180, 23, 0x404040, false);
        for (int i = 0; i < ProcessorBlockEntity.MAX_INPUTS; i++) {
            String role = i < handler.inputRoles().size() ? handler.inputRoles().get(i) : "-";
            role = textRenderer.trimToWidth(role, 20);
            context.drawText(textRenderer, role, 10 + i * 22, 28, 0x404040, false);
        }
        String outputItemId = handler.outputItemId();
        if (!outputItemId.isEmpty()) {
            int separator = outputItemId.indexOf(':');
            String shortOutputId = separator >= 0 ? outputItemId.substring(separator + 1) : outputItemId;
            context.drawText(textRenderer, textRenderer.trimToWidth(shortOutputId, 48), 174, 28, 0x404040, false);
        }
        String recipeLabel = handler.recipeId().isBlank()
                ? Text.translatable("gui.itemparams_demo.no_recipes").getString()
                : handler.recipeId() + " (" + (handler.recipeCount() == 0 ? 0 : handler.selectedIndex() + 1)
                    + "/" + handler.recipeCount() + ")";
        int availableWidth = 120;
        if (textRenderer.getWidth(recipeLabel) > availableWidth) {
            recipeLabel = textRenderer.trimToWidth(recipeLabel, availableWidth - 12) + "...";
        }
        context.drawText(textRenderer, recipeLabel, 35, 71, 0x202020, false);
        String statusKey = switch (handler.status()) {
            case ProcessorBlockEntity.WORKING -> "working";
            case ProcessorBlockEntity.OUTPUT_FULL -> "output_full";
            case ProcessorBlockEntity.INVALID_RECIPE -> "invalid_recipe";
            case ProcessorBlockEntity.ERROR -> "error";
            default -> "idle";
        };
        context.drawText(textRenderer, Text.translatable("gui.itemparams_demo." + statusKey), 8, 91, 0x404040, false);
        if (!handler.canChangeRecipe())
            context.drawText(textRenderer, Text.translatable("gui.itemparams_demo.recipe_locked"), 120, 91, 0x704040, false);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && handler.canChangeRecipe() && client != null && client.interactionManager != null) {
            double localX = mouseX - x;
            double localY = mouseY - y;
            if (localY >= 66 && localY <= 84) {
                if (localX >= 8 && localX <= 29) {
                    client.interactionManager.clickButton(handler.syncId, 0);
                    return true;
                }
                if (localX >= 160 && localX <= 198 && handler.canReconfigure()) {
                    client.interactionManager.clickButton(handler.syncId, 2);
                    return true;
                }
                if (localX >= 201 && localX <= 222) {
                    client.interactionManager.clickButton(handler.syncId, 1);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static void drawSlot(DrawContext context, int left, int top) {
        context.fill(left - 1, top - 1, left + 17, top + 17, 0xFF373737);
        context.fill(left, top, left + 17, top + 17, 0xFFFFFFFF);
        context.fill(left, top, left + 16, top + 16, 0xFF8B8B8B);
    }

    private static void drawButton(DrawContext context, int left, int top, int right, int bottom,
                                   String label, boolean enabled) {
        int fill = enabled ? 0xFFEEEEEE : 0xFFAAAAAA;
        context.fill(left, top, right, bottom, fill);
        context.fill(left, top, right, top + 1, 0xFFFFFFFF);
        context.fill(left, top, left + 1, bottom, 0xFFFFFFFF);
        context.fill(right - 1, top, right, bottom, 0xFF555555);
        context.fill(left, bottom - 1, right, bottom, 0xFF555555);
        context.drawText(MinecraftClient.getInstance().textRenderer, label, left + 7, top + 5,
                enabled ? 0x202020 : 0x666666, false);
    }
}

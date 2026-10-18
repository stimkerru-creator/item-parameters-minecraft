package dev.itemparams.fabric.machine;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public final class ProcessorBlock extends BlockWithEntity {
    public ProcessorBlock(Settings settings) { super(settings); }

    @Override public BlockRenderType getRenderType(BlockState state) { return BlockRenderType.MODEL; }
    @Override public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ProcessorBlockEntity(pos, state);
    }
    @Override public ActionResult onUse(BlockState state, World world, BlockPos pos,
                                       PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!world.isClient && world.getBlockEntity(pos) instanceof ProcessorBlockEntity processor) {
            player.openHandledScreen(processor);
        }
        return ActionResult.SUCCESS;
    }
    @Override public void onStateReplaced(BlockState state, World world, BlockPos pos,
                                          BlockState next, boolean moved) {
        if (!state.isOf(next.getBlock())) {
            if (world.getBlockEntity(pos) instanceof ProcessorBlockEntity processor) {
                ItemScatterer.spawn(world, pos, processor);
                world.updateComparators(pos, this);
            }
            super.onStateReplaced(state, world, pos, next, moved);
        }
    }
    @Override @Nullable public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient ? null : checkType(type, ProcessorRegistry.BLOCK_ENTITY_TYPE,
                ProcessorBlockEntity::tick);
    }
}

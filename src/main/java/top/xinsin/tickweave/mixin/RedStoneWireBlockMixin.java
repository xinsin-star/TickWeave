package top.xinsin.tickweave.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.ExperimentalRedstoneWireEvaluator;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.xinsin.tickweave.server.WireContext;

/**
 * 给红石粉求值建立一个短暂上下文。求值期间若通过邻居通知触发活塞入队，
 * ServerLevelMixin 就能读出“是哪一根粉线直接触发了它”。
 * 默认与实验性红石更新器都有各自的调用点，所以两个调用都要包住。
 */
@Mixin(RedStoneWireBlock.class)
public abstract class RedStoneWireBlockMixin {
    @Redirect(method = "updatePowerStrength", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/redstone/RedstoneWireEvaluator;updatePowerStrength(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/redstone/Orientation;Z)V"))
    private void tickWeave$defaultContext(RedstoneWireEvaluator evaluator, Level level, BlockPos pos,
                                            BlockState state, Orientation orientation, boolean shapeUpdate) {
        tickWeave$withContext(evaluator, level, pos, state, orientation, shapeUpdate);
    }

    @Redirect(method = "updatePowerStrength", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/redstone/ExperimentalRedstoneWireEvaluator;updatePowerStrength(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/redstone/Orientation;Z)V"))
    private void tickWeave$experimentalContext(ExperimentalRedstoneWireEvaluator evaluator, Level level, BlockPos pos,
                                                 BlockState state, Orientation orientation, boolean shapeUpdate) {
        tickWeave$withContext(evaluator, level, pos, state, orientation, shapeUpdate);
    }

    private static void tickWeave$withContext(RedstoneWireEvaluator evaluator, Level level, BlockPos pos,
                                                BlockState state, Orientation orientation, boolean shapeUpdate) {
        if (!(level instanceof ServerLevel server) || !server.tickRateManager().isFrozen()) {
            evaluator.updatePowerStrength(level, pos, state, orientation, shapeUpdate);
            return;
        }
        // 红石求值可以递归进入另一根粉线，使用栈而不是单个静态坐标。
        WireContext.push(pos);
        try {
            evaluator.updatePowerStrength(level, pos, state, orientation, shapeUpdate);
        } finally {
            WireContext.pop();
        }
    }
}

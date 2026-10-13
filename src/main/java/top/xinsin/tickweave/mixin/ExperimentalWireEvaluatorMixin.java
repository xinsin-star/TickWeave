package top.xinsin.tickweave.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.ExperimentalRedstoneWireEvaluator;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.xinsin.tickweave.server.TraceCollector;

/**
 * 与 DefaultWireEvaluatorMixin 作用相同，但挂在实验性红石更新器上。
 * 两者分别覆盖 Minecraft 两种红石实现；世界采用哪种规则就记录哪种。
 */
@Mixin(ExperimentalRedstoneWireEvaluator.class)
public abstract class ExperimentalWireEvaluatorMixin {
    /** 保留电平不变但被求值的记录，用来寻找可能的深度使用位置。 */
    @Inject(method = "updatePowerStrength", at = @At("TAIL"))
    private void tickWeave$recordCheck(Level level, BlockPos pos, BlockState oldState,
                                         Orientation orientation, boolean shapeUpdate, CallbackInfo ci) {
        if (!(level instanceof ServerLevel server) || !(level.getBlockState(pos).getBlock() instanceof RedStoneWireBlock)) return;
        int oldPower = oldState.getValue(RedStoneWireBlock.POWER);
        int newPower = level.getBlockState(pos).getValue(RedStoneWireBlock.POWER);
        if (oldPower == newPower) ((TraceCollector) server).tickWeave$recordWire(pos, oldPower, newPower);
    }

    @Redirect(method = "updatePowerStrength", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean tickWeave$recordWire(Level level, BlockPos pos, BlockState state, int flags) {
        // 返回原版 setBlock 的结果，Mixin 只旁路观察，不改红石传播规则。
        int oldPower = level.getBlockState(pos).getValue(RedStoneWireBlock.POWER);
        boolean changed = level.setBlock(pos, state, flags);
        if (changed && level instanceof ServerLevel server) {
            ((TraceCollector) server).tickWeave$recordWire(pos, oldPower, state.getValue(RedStoneWireBlock.POWER));
        }
        return changed;
    }
}

package top.xinsin.pistondepth.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.DefaultRedstoneWireEvaluator;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.xinsin.pistondepth.server.TraceCollector;

/** 观察原版默认红石线求值器；只记录求值结果，不改变原版电平计算。 */
@Mixin(DefaultRedstoneWireEvaluator.class)
public abstract class DefaultWireEvaluatorMixin {
    /** 电平没变也可能代表“这根线在当前深度被检查过”，因此在方法结束时补记。 */
    @Inject(method = "updatePowerStrength", at = @At("TAIL"))
    private void pistonDepth$recordCheck(Level level, BlockPos pos, BlockState oldState,
                                         Orientation orientation, boolean shapeUpdate, CallbackInfo ci) {
        if (!(level instanceof ServerLevel server) || !(level.getBlockState(pos).getBlock() instanceof RedStoneWireBlock)) return;
        int oldPower = oldState.getValue(RedStoneWireBlock.POWER);
        int newPower = level.getBlockState(pos).getValue(RedStoneWireBlock.POWER);
        if (oldPower == newPower) ((TraceCollector) server).pistonDepth$recordWire(pos, oldPower, newPower);
    }

    @Redirect(method = "updatePowerStrength", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean pistonDepth$recordWire(Level level, BlockPos pos, BlockState state, int flags) {
        // 先执行原版 setBlock，再记录真实发生的电平变化；返回值保持原样。
        int oldPower = level.getBlockState(pos).getValue(RedStoneWireBlock.POWER);
        boolean changed = level.setBlock(pos, state, flags);
        if (changed && level instanceof ServerLevel server) {
            ((TraceCollector) server).pistonDepth$recordWire(pos, oldPower, state.getValue(RedStoneWireBlock.POWER));
        }
        return changed;
    }
}

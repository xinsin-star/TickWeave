package top.xinsin.tickweave.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 直接调用原版活塞判定供电的方法。方块事件入队和执行时各读一次，
 * 才能区分“有电时入队，轮到执行时已失电”和“从来没有通过供电检查”。
 * 这里不复制准连接性或邻居信号算法，以免诊断与 26.1.2 原版不同。
 */
@Mixin(PistonBaseBlock.class)
public interface PistonPowerAccessor {
    @Invoker("getNeighborSignal")
    boolean tickWeave$getNeighborSignal(SignalGetter level, BlockPos pos, Direction facing);
}

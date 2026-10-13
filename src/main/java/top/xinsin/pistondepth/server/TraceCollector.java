package top.xinsin.pistondepth.server;

import net.minecraft.core.BlockPos;

/** 红石求值 Mixin 通过该接口向 ServerLevelMixin 报告观测结果。 */
public interface TraceCollector {
    void pistonDepth$recordWire(BlockPos pos, int oldPower, int newPower);
}

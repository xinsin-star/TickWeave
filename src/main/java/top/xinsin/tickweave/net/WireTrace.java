package top.xinsin.tickweave.net;

import net.minecraft.core.BlockPos;

/**
 * 一次红石粉求值观测。oldPower == newPower 表示被检查但电平不变；
 * source == null 表示无法归属到正在执行的方块事件，不表示已识别为重置线。
 * sourceSequence 是产生本次红石求值的方块事件序号；-1 表示没有此类父事件。
 */
public record WireTrace(
        BlockPos pos,
        BlockPos source,
        int sourceSequence,
        long chain,
        int depth,
        int sequence,
        int oldPower,
        int newPower
) { }

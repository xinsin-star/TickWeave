package top.xinsin.tickweave.net;

import net.minecraft.core.BlockPos;

/**
 * 服务端实际处理过的方块事件，而非预测结果。
 * depth 为内部零起始因果深度；order 为本刻“活塞”执行序号；sequence
 * 同时覆盖方块事件与红石观测；produced 是该事件执行时成功入队的子事件数。
 * parentSequence 指向本游戏刻内真正入队本事件的父事件，-1 表示根事件；
 * parent/triggeringWire 分别用于父子箭头和红石粉来源箭头。
 * extendedBefore/extendedAfter 是事件执行前后的活塞方块状态：事件返回 true
 * 并不一定意味着方块真的从未伸出变成伸出，故单独保存这个观测值。
 * redstoneFront/Second 是活塞朝向第 1/2 格是否有红石块，含正在移动且
 * movedState 为红石块的方块实体。它们表示位置，不能仅凭动作返回值推断。
 * powerAtQueue/powerAtExecution 使用原版活塞供电检查，-1 表示当时无法判定；
 * blockMatched 表示执行前事件所指的活塞方块仍在原位。
 */
public record TraceEntry(
        BlockPos pos,
        BlockPos parent,
        BlockPos triggeringWire,
        long chain,
        int depth,
        int parentSequence,
        int order,
        int sequence,
        int action,
        int facing,
        int powerAtQueue,
        int powerAtExecution,
        boolean blockMatched,
        boolean succeeded,
        boolean extendedBefore,
        boolean extendedAfter,
        boolean redstoneFrontBefore,
        boolean redstoneSecondBefore,
        boolean redstoneFrontAfter,
        boolean redstoneSecondAfter,
        int produced,
        int duplicateAttempts,
        String blockId,
        boolean piston
) {
    // 不在网络数据模型里生成显示文字：动作名由客户端根据当前游戏语言翻译。
}

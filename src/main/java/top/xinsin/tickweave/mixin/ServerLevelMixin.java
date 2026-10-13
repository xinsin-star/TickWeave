package top.xinsin.tickweave.mixin;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.xinsin.tickweave.net.TraceEntry;
import top.xinsin.tickweave.net.WireTrace;
import top.xinsin.tickweave.server.TraceBroadcaster;
import top.xinsin.tickweave.server.TraceCollector;
import top.xinsin.tickweave.server.WireContext;

/**
 * 深度来自服务端“方块事件入队 -> 方块事件执行”的因果关系。
 * 只看方块位置或红石电平无法还原这一关系：同一活塞可能在一刻内执行多次，
 * 而同一根红石线也可能被不同事件链反复求值。因此这里同时拦截入队和执行点。
 * 内部深度从 0 开始，客户端显示时统一加 1。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements TraceCollector {
    @Shadow private boolean doBlockEvent(BlockEventData data) { throw new AssertionError(); }

    // pending 保存已入队但尚未执行的事件；current 是当前正在执行的父事件。
    @Unique private final Map<BlockEventData, EventMeta> tickWeave$pending = new HashMap<>();
    @Unique private final List<TraceEntry> tickWeave$processed = new ArrayList<>();
    @Unique private final List<WireTrace> tickWeave$wires = new ArrayList<>();
    @Unique private final Set<WireCheckKey> tickWeave$wireChecks = new HashSet<>();
    @Unique private EventMeta tickWeave$current;
    // chain 区分同一游戏刻中互不相关的根事件；order 只给活塞编号。
    @Unique private long tickWeave$nextChain = 1;
    @Unique private long tickWeave$lastTick = Long.MIN_VALUE;
    @Unique private int tickWeave$order;
    @Unique private int tickWeave$sequence;

    @Unique private void tickWeave$ensureTick(long tick) {
        // 方块事件和红石线共用 sequence，以保留同一游戏刻内的观测先后。
        if (tick != tickWeave$lastTick) {
            tickWeave$lastTick = tick;
            tickWeave$order = 0;
            tickWeave$sequence = 0;
        }
    }

    /**
     * 原版集合 add 返回 true 才表示事件真的进入队列；重复事件会被去重。
     * 在父事件的 doBlockEvent 执行期间入队的新事件继承 chain，深度加 1。
     * WireContext.current() 则保留当时正在求值的红石粉坐标，供橙色箭头使用。
     */
    @Redirect(method = "blockEvent", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;add(Ljava/lang/Object;)Z"))
    private boolean tickWeave$onQueue(ObjectLinkedOpenHashSet<BlockEventData> events, Object value) {
        boolean added = events.add((BlockEventData) value);
        ServerLevel level = (ServerLevel) (Object) this;
        if (level.tickRateManager().isFrozen() && value instanceof BlockEventData event) {
            EventMeta parent = tickWeave$current;
            if (parent != null) {
                if (added) parent.produced++;
                else parent.duplicateAttempts++;
            }
            if (added) tickWeave$pending.put(event, new EventMeta(parent == null ? tickWeave$nextChain++ : parent.chain,
                    parent == null ? 0 : parent.depth + 1, parent == null ? null : parent.pos,
                    parent == null ? -1 : parent.sequence, WireContext.current(), event.pos(), level.getGameTime(),
                    tickWeave$power(level, event)));
        }
        return added;
    }

    /**
     * 包住真正的 doBlockEvent 调用，使执行过程中产生的子事件能看到 current。
     * 某些事件可能在前一刻或冻结前入队；这时不沿用过期元数据，而以新根处理。
     */
    @Redirect(method = "runBlockEvents", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;doBlockEvent(Lnet/minecraft/world/level/BlockEventData;)Z"))
    private boolean tickWeave$onProcess(ServerLevel level, BlockEventData event) {
        if (!level.tickRateManager().isFrozen()) {
            tickWeave$pending.remove(event);
            return doBlockEvent(event);
        }
        EventMeta previous = tickWeave$current;
        EventMeta meta = tickWeave$pending.remove(event);
        if (meta == null || meta.tick != level.getGameTime()) {
            meta = new EventMeta(tickWeave$nextChain++, 0, null, -1, null, event.pos(), level.getGameTime(), -1);
        }
        tickWeave$current = meta;
        try {
            tickWeave$ensureTick(level.getGameTime());
            boolean piston = event.block() instanceof PistonBaseBlock;
            BlockState beforeState = level.getBlockState(event.pos());
            Direction facing = piston && beforeState.getBlock() instanceof PistonBaseBlock
                    ? beforeState.getValue(PistonBaseBlock.FACING) : null;
            boolean blockMatched = beforeState.is(event.block());
            int powerAtExecution = tickWeave$power(level, event);
            int order = piston ? ++tickWeave$order : 0;
            int sequence = ++tickWeave$sequence;
            meta.sequence = sequence;
            // 方块事件的返回值表示事件处理结果，不能单凭 true 推定活塞真的伸出。
            // 分别读取前后方块状态，供客户端判定本次事件有无实体状态变化。
            boolean extendedBefore = tickWeave$isExtended(level, event.pos(), piston);
            RedstoneNearPiston redstoneBefore = tickWeave$redstoneNear(level, event.pos(), facing);
            boolean succeeded = doBlockEvent(event);
            boolean extendedAfter = tickWeave$isExtended(level, event.pos(), piston);
            RedstoneNearPiston redstoneAfter = tickWeave$redstoneNear(level, event.pos(), facing);
            // 除活塞外，只有参与因果链的方块事件才发送给客户端，减少无关标签。
            if (piston || meta.produced > 0 || meta.depth > 0) {
                tickWeave$processed.add(new TraceEntry(event.pos(), meta.parent, meta.triggeringWire, meta.chain, meta.depth,
                        meta.parentSequence, order, sequence, event.paramA(),
                        facing == null ? -1 : facing.get3DDataValue(), meta.powerAtQueue, powerAtExecution,
                        blockMatched, succeeded, extendedBefore, extendedAfter,
                        redstoneBefore.front, redstoneBefore.second, redstoneAfter.front, redstoneAfter.second,
                        meta.produced, meta.duplicateAttempts, BuiltInRegistries.BLOCK.getKey(event.block()).toString(), piston));
            }
            return succeeded;
        } finally {
            tickWeave$current = previous;
        }
    }

    @Unique private static boolean tickWeave$isExtended(ServerLevel level, BlockPos pos, boolean pistonEvent) {
        if (!pistonEvent) return false;
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof PistonBaseBlock && state.getValue(PistonBaseBlock.EXTENDED);
    }

    /** -1 表示不是仍存在的活塞；0/1 使用原版供电判定。 */
    @Unique private static int tickWeave$power(ServerLevel level, BlockEventData event) {
        if (!(event.block() instanceof PistonBaseBlock piston)) return -1;
        BlockState state = level.getBlockState(event.pos());
        if (!state.is(event.block())) return -1;
        Direction facing = state.getValue(PistonBaseBlock.FACING);
        return ((PistonPowerAccessor) piston).tickWeave$getNeighborSignal(level, event.pos(), facing) ? 1 : 0;
    }

    @Unique private static RedstoneNearPiston tickWeave$redstoneNear(ServerLevel level, BlockPos pos, Direction facing) {
        if (facing == null) return new RedstoneNearPiston(false, false);
        return new RedstoneNearPiston(tickWeave$isRedstoneBlock(level, pos.relative(facing)),
                tickWeave$isRedstoneBlock(level, pos.relative(facing, 2)));
    }

    /** 移动中的红石块在世界里暂时是 MOVING_PISTON，要读取其方块实体才不会漏判。 */
    @Unique private static boolean tickWeave$isRedstoneBlock(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.REDSTONE_BLOCK)) return true;
        return state.is(Blocks.MOVING_PISTON)
                && level.getBlockEntity(pos) instanceof PistonMovingBlockEntity moving
                && moving.getMovedState().is(Blocks.REDSTONE_BLOCK);
    }

    @Inject(method = "runBlockEvents", at = @At("TAIL"))
    private void tickWeave$sendTrace(CallbackInfo ci) {
        // 本刻全部方块事件处理完毕后统一广播，再清空临时缓冲。
        ServerLevel level = (ServerLevel) (Object) this;
        TraceBroadcaster.send(level, level.getGameTime(), tickWeave$processed, tickWeave$wires);
        tickWeave$processed.clear();
        tickWeave$wires.clear();
        tickWeave$wireChecks.clear();
    }

    @Override
    public void tickWeave$recordWire(BlockPos pos, int oldPower, int newPower) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (!level.tickRateManager().isFrozen() || tickWeave$wires.size() >= 8192) return;
        tickWeave$ensureTick(level.getGameTime());
        EventMeta source = tickWeave$current;
        // 无父事件且电平不变的检查量很大，也无法归入活塞链，直接忽略。
        if (source == null && oldPower == newPower) return;
        BlockPos stablePos = pos.immutable();
        BlockPos sourcePos = source == null ? null : source.pos;
        long chain = source == null ? 0 : source.chain;
        int depth = source == null ? 0 : source.depth;
        // 同一来源对同一根线的重复“不变检查”只记一次；真实电平变化逐次保留。
        if (oldPower == newPower && !tickWeave$wireChecks.add(new WireCheckKey(stablePos, sourcePos, chain, depth))) return;
        tickWeave$wires.add(new WireTrace(stablePos, sourcePos, source == null ? -1 : source.sequence, chain, depth,
                ++tickWeave$sequence, oldPower, newPower));
    }

    /** 入队时确定的元数据；produced 在该事件执行时由子事件入队次数累加。 */
    @Unique private static final class EventMeta {
        private final long chain;
        private final int depth;
        private final BlockPos parent;
        private final int parentSequence;
        private final BlockPos triggeringWire;
        private final BlockPos pos;
        private final long tick;
        private final int powerAtQueue;
        private int produced;
        private int duplicateAttempts;
        private int sequence = -1;

        private EventMeta(long chain, int depth, BlockPos parent, int parentSequence,
                          BlockPos triggeringWire, BlockPos pos, long tick, int powerAtQueue) {
            this.chain = chain;
            this.depth = depth;
            this.parent = parent;
            this.parentSequence = parentSequence;
            this.triggeringWire = triggeringWire;
            this.pos = pos;
            this.tick = tick;
            this.powerAtQueue = powerAtQueue;
        }
    }

    @Unique private record WireCheckKey(BlockPos pos, BlockPos source, long chain, int depth) { }
    @Unique private record RedstoneNearPiston(boolean front, boolean second) { }
}

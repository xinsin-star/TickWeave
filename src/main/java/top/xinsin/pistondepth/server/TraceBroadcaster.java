package top.xinsin.pistondepth.server;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.xinsin.pistondepth.net.TraceEntry;
import top.xinsin.pistondepth.net.TracePacket;
import top.xinsin.pistondepth.net.WireTrace;

/** 把服务端的一刻记录按玩家距离筛选、分片，再发送给装有同版本模组的客户端。 */
public final class TraceBroadcaster {
    private static final double RADIUS_SQUARED = 96.0 * 96.0;
    private static final Logger LOGGER = LoggerFactory.getLogger("PistonDepth");

    private TraceBroadcaster() { }

    public static void send(ServerLevel level, long tick, List<TraceEntry> processed,
                            List<WireTrace> wires) {
        // 空刻不发包，否则客户端“最新记录”会不断被空白刻覆盖。
        if (!level.tickRateManager().isFrozen() || (processed.isEmpty() && wires.isEmpty())) return;
        // 调试日志保留内部零起始深度，便于逐事件核对；普通游玩默认关闭。
        if (Boolean.getBoolean("piston_depth.debug")) {
            LOGGER.info("tick {}: {} block events, {} wire observations", tick,
                    processed.size(), wires.size());
            for (TraceEntry entry : processed) {
                LOGGER.info("  {} {} D{} +{} chain={} seq={} parentSeq={} parent={} action={} pos={} power={}->{} extended={}->{} accepted={}",
                        entry.piston() ? "piston" : entry.blockId(), entry.order(), entry.depth(),
                        entry.produced(), entry.chain(), entry.sequence(), entry.parentSequence(), entry.parent(),
                        entry.action(), entry.pos(), entry.powerAtQueue(), entry.powerAtExecution(),
                        entry.extendedBefore(), entry.extendedAfter(), entry.succeeded());
            }
            for (WireTrace wire : wires) {
                if (wire.source() != null && wire.oldPower() != wire.newPower()) LOGGER.info("  wire D{} {}->{} chain={} seq={} pos={} source={} sourceSeq={}",
                        wire.depth(), wire.oldPower(), wire.newPower(), wire.chain(), wire.sequence(), wire.pos(),
                        wire.source(), wire.sourceSequence());
            }
        }
        for (ServerPlayer player : level.players()) {
            if (!ServerPlayNetworking.canSend(player, TracePacket.TYPE)) continue;
            // 按玩家逐个筛选：多人服务器上不广播远处电路的全部更新。
            List<TraceEntry> nearby = new ArrayList<>();
            List<WireTrace> nearbyWires = new ArrayList<>();
            for (TraceEntry entry : processed) {
                if (entry.pos().distSqr(player.blockPosition()) <= RADIUS_SQUARED) nearby.add(entry);
            }
            for (WireTrace wire : wires) {
                if (wire.pos().distSqr(player.blockPosition()) <= RADIUS_SQUARED) nearbyWires.add(wire);
            }
            if (nearby.isEmpty() && nearbyWires.isEmpty()) continue;
            int eventPackets = (nearby.size() + TracePacket.MAX_ENTRIES - 1) / TracePacket.MAX_ENTRIES;
            int wirePackets = (nearbyWires.size() + TracePacket.MAX_WIRES - 1) / TracePacket.MAX_WIRES;
            int packets = Math.max(1, Math.max(eventPackets, wirePackets));
            // 两类记录按各自上限切片，客户端再用 tick 合并同一刻的多个包。
            for (int index = 0; index < packets; index++) {
                int eventFrom = Math.min(index * TracePacket.MAX_ENTRIES, nearby.size());
                int eventTo = Math.min(eventFrom + TracePacket.MAX_ENTRIES, nearby.size());
                int wireFrom = Math.min(index * TracePacket.MAX_WIRES, nearbyWires.size());
                int wireTo = Math.min(wireFrom + TracePacket.MAX_WIRES, nearbyWires.size());
                ServerPlayNetworking.send(player, new TracePacket(tick,
                        List.copyOf(nearby.subList(eventFrom, eventTo)),
                        List.copyOf(nearbyWires.subList(wireFrom, wireTo))));
            }
        }
    }
}

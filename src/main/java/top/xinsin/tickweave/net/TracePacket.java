package top.xinsin.tickweave.net;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 同一游戏刻的一个网络分片。保持协议只传数字、坐标和方块 ID，
 * 不传已翻译的文字：不同客户端可以按各自的游戏语言显示同一份服务器数据。
 */
public record TracePacket(long tick, List<TraceEntry> entries, List<WireTrace> wires) implements CustomPacketPayload {
    public static final Type<TracePacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath("tickweave", "trace_v8"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TracePacket> CODEC = StreamCodec.of(TracePacket::write, TracePacket::read);
    public static final int MAX_ENTRIES = 256;
    public static final int MAX_WIRES = 256;

    private static void write(RegistryFriendlyByteBuf buf, TracePacket packet) {
        // nullable 坐标先写存在标志，避免人为保留一个可能与真实坐标冲突的哨兵值。
        buf.writeVarLong(packet.tick);
        buf.writeVarInt(packet.entries.size());
        for (TraceEntry entry : packet.entries) {
            buf.writeBlockPos(entry.pos());
            buf.writeBoolean(entry.parent() != null);
            if (entry.parent() != null) buf.writeBlockPos(entry.parent());
            buf.writeBoolean(entry.triggeringWire() != null);
            if (entry.triggeringWire() != null) buf.writeBlockPos(entry.triggeringWire());
            buf.writeVarLong(entry.chain());
            buf.writeVarInt(entry.depth());
            buf.writeVarInt(entry.parentSequence());
            buf.writeVarInt(entry.order());
            buf.writeVarInt(entry.sequence());
            buf.writeVarInt(entry.action());
            buf.writeVarInt(entry.facing());
            buf.writeVarInt(entry.powerAtQueue());
            buf.writeVarInt(entry.powerAtExecution());
            buf.writeBoolean(entry.blockMatched());
            buf.writeBoolean(entry.succeeded());
            buf.writeBoolean(entry.extendedBefore());
            buf.writeBoolean(entry.extendedAfter());
            buf.writeBoolean(entry.redstoneFrontBefore());
            buf.writeBoolean(entry.redstoneSecondBefore());
            buf.writeBoolean(entry.redstoneFrontAfter());
            buf.writeBoolean(entry.redstoneSecondAfter());
            buf.writeVarInt(entry.produced());
            buf.writeVarInt(entry.duplicateAttempts());
            buf.writeUtf(entry.blockId(), 256);
            buf.writeBoolean(entry.piston());
        }
        buf.writeVarInt(packet.wires.size());
        for (WireTrace wire : packet.wires) {
            buf.writeBlockPos(wire.pos());
            buf.writeBoolean(wire.source() != null);
            if (wire.source() != null) buf.writeBlockPos(wire.source());
            buf.writeVarInt(wire.sourceSequence());
            buf.writeVarLong(wire.chain());
            buf.writeVarInt(wire.depth());
            buf.writeVarInt(wire.sequence());
            buf.writeVarInt(wire.oldPower());
            buf.writeVarInt(wire.newPower());
        }
    }

    private static TracePacket read(RegistryFriendlyByteBuf buf) {
        // 对不可信网络包的数组长度设上限，和服务端发送分片的常量保持一致。
        long tick = buf.readVarLong();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Invalid piston trace size: " + count);
        List<TraceEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = buf.readBlockPos();
            BlockPos parent = buf.readBoolean() ? buf.readBlockPos() : null;
            BlockPos triggeringWire = buf.readBoolean() ? buf.readBlockPos() : null;
            entries.add(new TraceEntry(pos, parent, triggeringWire, buf.readVarLong(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                    buf.readVarInt(), buf.readVarInt(), buf.readUtf(256), buf.readBoolean()));
        }
        int wireCount = buf.readVarInt();
        if (wireCount < 0 || wireCount > MAX_WIRES) throw new IllegalArgumentException("Invalid wire trace size: " + wireCount);
        List<WireTrace> wires = new ArrayList<>(wireCount);
        for (int i = 0; i < wireCount; i++) {
            BlockPos pos = buf.readBlockPos();
            BlockPos source = buf.readBoolean() ? buf.readBlockPos() : null;
            wires.add(new WireTrace(pos, source, buf.readVarInt(), buf.readVarLong(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt()));
        }
        return new TracePacket(tick, entries, wires);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

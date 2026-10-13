package top.xinsin.tickweave;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import top.xinsin.tickweave.net.TracePacket;

public final class TickWeaveMod implements ModInitializer {
    public static final String MOD_ID = "tickweave";

    @Override
    public void onInitialize() {
        // 客户端和服务端都注册同一 payload 类型；实际采集发生在服务端 Mixin。
        PayloadTypeRegistry.clientboundPlay().register(TracePacket.TYPE, TracePacket.CODEC);
    }
}

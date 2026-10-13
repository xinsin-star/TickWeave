package top.xinsin.pistondepth;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import top.xinsin.pistondepth.net.TracePacket;

public final class PistonDepthMod implements ModInitializer {
    public static final String MOD_ID = "piston_depth";

    @Override
    public void onInitialize() {
        // 客户端和服务端都注册同一 payload 类型；实际采集发生在服务端 Mixin。
        PayloadTypeRegistry.clientboundPlay().register(TracePacket.TYPE, TracePacket.CODEC);
    }
}

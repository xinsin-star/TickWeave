package top.xinsin.pistondepth.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.xinsin.pistondepth.client.PistonDepthClient;

/** 复用游戏的世界 Gizmo 绘制阶段，使文字和箭头跟随三维方块坐标。 */
@Mixin(DebugRenderer.class)
public abstract class DebugRendererMixin {
    @Inject(method = "emitGizmos", at = @At("TAIL"))
    private void pistonDepth$render(Frustum frustum, double cameraX, double cameraY, double cameraZ,
                                    float partialTick, CallbackInfo ci) {
        // 启用状态、冻结条件和距离过滤都在客户端入口统一检查。
        PistonDepthClient.emitWorldGizmos(cameraX, cameraY, cameraZ);
    }
}

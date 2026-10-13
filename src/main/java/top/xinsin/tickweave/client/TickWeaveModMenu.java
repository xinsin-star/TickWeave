package top.xinsin.tickweave.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/** 仅在 Mod Menu 存在时由它加载；MaLiLib 缺席时不提供无效的配置按钮。 */
public final class TickWeaveModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded("malilib")) return null;
        return MasaConfigBridge::createScreen;
    }
}

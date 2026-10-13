package top.xinsin.tickweave.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.gui.GuiModConfigs;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;

/**
 * 可选的 MaLiLib 适配层。TickWeaveClient 先检查 isModLoaded("malilib") 才调用它，
 * 因而没有安装 MaLiLib 的客户端仍能使用指令，专用服务器也不会链接到这些类。
 * MaLiLib 管理控件和按键检测；ClientSettings 负责数据和持久化。
 */
public final class MasaConfigBridge implements IConfigHandler, IKeybindProvider {
    private static final String MOD_ID = "tickweave";
    private static final MasaConfigBridge INSTANCE = new MasaConfigBridge();

    private final ConfigBoolean enabled = label(new ConfigBoolean("enabled", false), "enabled");
    private final ConfigBoolean showPistonEvents = label(new ConfigBoolean("showPistonEvents", true), "showPistonEvents");
    private final ConfigBoolean showWireUpdates = label(new ConfigBoolean("showWireUpdates", true), "showWireUpdates");
    private final ConfigDouble hudScale = label(new ConfigDouble("hudScale", 1.0, 0.5, 3.0), "hudScale");
    private final ConfigInteger hudX = label(new ConfigInteger("hudX", 5, 0, 1000), "hudX");
    private final ConfigInteger hudY = label(new ConfigInteger("hudY", 5, 0, 1000), "hudY");
    private final ConfigDouble worldTextScale = label(new ConfigDouble("worldTextScale", 1.0, 0.5, 5.0), "worldTextScale");
    private final ConfigDouble pistonNumberScale = label(new ConfigDouble("pistonNumberScale", 0.10, 0.02, 0.25), "pistonNumberScale");
    private final ConfigDouble detailScale = label(new ConfigDouble("detailScale", 0.05, 0.015, 0.15), "detailScale");
    private final ConfigDouble wireScale = label(new ConfigDouble("wireScale", 0.05, 0.015, 0.15), "wireScale");
    private final ConfigInteger renderDistance = label(new ConfigInteger("renderDistance", 64, 8, 96), "renderDistance");
    private final ConfigColor titleColor = color("titleColor", ClientSettings.DEFAULT_TITLE_COLOR);
    private final ConfigColor textColor = color("textColor", ClientSettings.DEFAULT_TEXT_COLOR);
    private final ConfigColor mutedColor = color("mutedColor", ClientSettings.DEFAULT_MUTED_COLOR);
    private final ConfigColor backgroundColor = color("backgroundColor", ClientSettings.DEFAULT_BACKGROUND_COLOR);
    private final ConfigColor triggerArrowColor = color("triggerArrowColor", ClientSettings.DEFAULT_TRIGGER_ARROW_COLOR);
    private final ConfigColor[] depthColors = new ConfigColor[6];
    private final ConfigHotkey toggleKey = label(new ConfigHotkey("toggleKey", ""), "toggleKey");
    private final ConfigHotkey previousKey = label(new ConfigHotkey("previousKey", ""), "previousKey");
    private final ConfigHotkey nextKey = label(new ConfigHotkey("nextKey", ""), "nextKey");
    private final ConfigHotkey configKey = label(new ConfigHotkey("configKey", ""), "configKey");
    private List<IConfigBase> options;
    private List<ConfigHotkey> hotkeyOptions;
    private boolean syncing;

    private MasaConfigBridge() {
        for (int i = 0; i < depthColors.length; i++) {
            depthColors[i] = color("depthColor" + (i + 1), ClientSettings.defaultDepthColor(i));
        }
        hotkeyOptions = List.of(toggleKey, previousKey, nextKey, configKey);
        // 总体世界文字大小放在最前面，进入页面无需滚动即可找到。
        options = List.of(enabled, showPistonEvents, showWireUpdates,
                worldTextScale, hudScale, hudX, hudY, pistonNumberScale, detailScale,
                wireScale, renderDistance, titleColor, textColor, mutedColor, backgroundColor,
                triggerArrowColor, depthColors[0], depthColors[1], depthColors[2], depthColors[3],
                depthColors[4], depthColors[5], toggleKey, previousKey, nextKey, configKey);
    }

    public static void install() {
        // 先从磁盘同步控件，然后注册回调；加载旧值时不会被误判成用户修改。
        INSTANCE.syncFromSettings();
        for (IConfigBase option : INSTANCE.options) {
            if (option instanceof ConfigBoolean c) c.setValueChangeCallback(ignored -> INSTANCE.apply());
            else if (option instanceof ConfigDouble c) c.setValueChangeCallback(ignored -> INSTANCE.apply());
            else if (option instanceof ConfigInteger c) c.setValueChangeCallback(ignored -> INSTANCE.apply());
            else if (option instanceof ConfigColor c) c.setValueChangeCallback(ignored -> INSTANCE.apply());
        }
        INSTANCE.toggleKey.getKeybind().setCallback((action, key) -> { TickWeaveClient.toggleEnabled(); return true; });
        INSTANCE.previousKey.getKeybind().setCallback((action, key) -> { TickWeaveClient.previousTick(); return true; });
        INSTANCE.nextKey.getKeybind().setCallback((action, key) -> { TickWeaveClient.nextTick(); return true; });
        INSTANCE.configKey.getKeybind().setCallback((action, key) -> { open(Minecraft.getInstance().screen); return true; });
        ConfigManager.getInstance().registerConfigHandler(MOD_ID, INSTANCE);
        InputEventHandler.getKeybindManager().registerKeybindProvider(INSTANCE);
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(MOD_ID, "TickWeave", () -> createScreen(null)));
    }

    /** 从指令或 Mod Menu 打开时，把当前画面设为父页面，ESC 会返回原页面。 */
    public static void open(Screen parent) {
        GuiBase.openGui(createScreen(parent));
    }

    public static GuiModConfigs createScreen(Screen parent) {
        INSTANCE.syncEnabledOption();
        return (GuiModConfigs) new GuiModConfigs(MOD_ID, INSTANCE.options,
                I18n.get("config.tickweave.title")).setParent(parent);
    }

    /** 指令会改变同一开关；同步控件，避免下次打开页面显示旧状态。 */
    public static void syncEnabled() {
        INSTANCE.syncEnabledOption();
    }

    private void syncEnabledOption() {
        syncing = true;
        enabled.setBooleanValue(ClientSettings.enabled);
        showPistonEvents.setBooleanValue(ClientSettings.showPistonEvents);
        showWireUpdates.setBooleanValue(ClientSettings.showWireUpdates);
        syncing = false;
    }

    private void syncFromSettings() {
        syncing = true;
        enabled.setBooleanValue(ClientSettings.enabled);
        showPistonEvents.setBooleanValue(ClientSettings.showPistonEvents);
        showWireUpdates.setBooleanValue(ClientSettings.showWireUpdates);
        hudScale.setDoubleValue(ClientSettings.hudScale);
        hudX.setIntegerValue(ClientSettings.hudX);
        hudY.setIntegerValue(ClientSettings.hudY);
        worldTextScale.setDoubleValue(ClientSettings.worldTextScale);
        pistonNumberScale.setDoubleValue(ClientSettings.pistonNumberScale);
        detailScale.setDoubleValue(ClientSettings.detailScale);
        wireScale.setDoubleValue(ClientSettings.wireScale);
        renderDistance.setIntegerValue(ClientSettings.renderDistance);
        titleColor.setIntegerValue(ClientSettings.titleColor);
        textColor.setIntegerValue(ClientSettings.textColor);
        mutedColor.setIntegerValue(ClientSettings.mutedColor);
        backgroundColor.setIntegerValue(ClientSettings.backgroundColor);
        triggerArrowColor.setIntegerValue(ClientSettings.triggerArrowColor);
        for (int i = 0; i < depthColors.length; i++) depthColors[i].setIntegerValue(ClientSettings.depthColors[i]);
        JsonObject savedHotkeys = ClientSettings.hotkeys();
        for (ConfigHotkey hotkey : hotkeyOptions) {
            JsonElement saved = savedHotkeys.get(hotkey.getName());
            if (saved != null) hotkey.setValueFromJsonElement(saved);
        }
        syncing = false;
    }

    private void apply() {
        if (syncing) return;
        // 控件的变更回调在值改变时立即执行，因此画面可以实时预览。
        ClientSettings.enabled = enabled.getBooleanValue();
        ClientSettings.showPistonEvents = showPistonEvents.getBooleanValue();
        ClientSettings.showWireUpdates = showWireUpdates.getBooleanValue();
        ClientSettings.hudScale = hudScale.getDoubleValue();
        ClientSettings.hudX = hudX.getIntegerValue();
        ClientSettings.hudY = hudY.getIntegerValue();
        ClientSettings.worldTextScale = worldTextScale.getDoubleValue();
        ClientSettings.pistonNumberScale = pistonNumberScale.getDoubleValue();
        ClientSettings.detailScale = detailScale.getDoubleValue();
        ClientSettings.wireScale = wireScale.getDoubleValue();
        ClientSettings.renderDistance = renderDistance.getIntegerValue();
        ClientSettings.titleColor = titleColor.getIntegerValue();
        ClientSettings.textColor = textColor.getIntegerValue();
        ClientSettings.mutedColor = mutedColor.getIntegerValue();
        ClientSettings.backgroundColor = backgroundColor.getIntegerValue();
        ClientSettings.triggerArrowColor = triggerArrowColor.getIntegerValue();
        for (int i = 0; i < depthColors.length; i++) ClientSettings.depthColors[i] = depthColors[i].getIntegerValue();
        ClientSettings.save();
    }

    @Override
    public void load() {
        ClientSettings.load();
        syncFromSettings();
    }

    @Override
    public void save() {
        JsonObject saved = new JsonObject();
        for (ConfigHotkey hotkey : hotkeyOptions) saved.add(hotkey.getName(), hotkey.getAsJsonElement());
        ClientSettings.setHotkeys(saved);
    }

    @Override
    public void onConfigsChanged() {
        apply();
        save();
        InputEventHandler.getKeybindManager().updateUsedKeys();
    }

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        for (ConfigHotkey hotkey : hotkeyOptions) manager.addKeybindToMap(hotkey.getKeybind());
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        manager.addHotkeysForCategory(MOD_ID, "TickWeave", List.copyOf(hotkeyOptions));
    }

    private static ConfigColor color(String name, int argb) {
        return label(new ConfigColor(name, ClientSettings.hex(argb)), name);
    }

    private static <T extends IConfigBase> T label(T option, String name) {
        // MaLiLib 翻译这些 key；Minecraft 自行按当前游戏语言选中/英文。
        option.setPrettyName("config.tickweave.prettyName." + name);
        option.setTranslatedName("config.tickweave.name." + name);
        option.setComment("config.tickweave.comment." + name);
        return option;
    }

}

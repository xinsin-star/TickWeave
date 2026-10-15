package top.xinsin.tickweave.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import top.xinsin.tickweave.net.TraceEntry;
import top.xinsin.tickweave.net.TracePacket;
import top.xinsin.tickweave.net.WireTrace;

/**
 * 客户端只负责接收和展示，不自行推算活塞深度。权威的事件顺序由服务端采集，
 * 因此单人和多人看到的都是同一套数据。界面文字走 Minecraft 语言资源，
 * 游戏切换语言并完成资源重载后会直接读取新译文，不需要重连或重新执行指令。
 */
public final class TickWeaveClient implements ClientModInitializer {
    private static final int MAX_TICKS = 40;
    private static final int MAX_INSPECT_LINES = 48;
    private static final NavigableMap<Long, TickTrace> HISTORY = new TreeMap<>();
    private static boolean wasFrozen;
    private static int selectedOffset;

    @Override
    public void onInitializeClient() {
        ClientSettings.load();
        // MaLiLib 只安装在客户端。其类仅在确实存在时才链接和初始化。
        if (FabricLoader.getInstance().isModLoaded("malilib")) MasaConfigBridge.install();
        // 网络回调不保证在渲染线程；把历史记录的修改切回客户端主线程。
        ClientPlayNetworking.registerGlobalReceiver(TracePacket.TYPE,
                (packet, context) -> context.client().execute(() -> accept(packet)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            clear();
            wasFrozen = false;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean frozen = client.level != null && client.level.tickRateManager().isFrozen();
            // 每次新进入冻结状态时开始一次新的观察，避免上一轮实验混入。
            if (frozen && !wasFrozen) clear();
            wasFrozen = frozen;
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("tickweave", "hud"), TickWeaveClient::renderHud);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("tickweave")
                        .executes(ctx -> feedback(ctx.getSource(), "usage"))
                        .then(literal("on").executes(ctx -> {
                            setEnabled(true);
                            return feedback(ctx.getSource(), "enabled");
                        }))
                        .then(literal("off").executes(ctx -> {
                            setEnabled(false);
                            return feedback(ctx.getSource(), "disabled");
                        }))
                        .then(literal("prev").executes(ctx -> {
                            previousTick();
                            return feedback(ctx.getSource(), "older", selectedOffset);
                        }))
                        .then(literal("next").executes(ctx -> {
                            nextTick();
                            return feedback(ctx.getSource(), "newer", selectedOffset);
                        }))
                        .then(literal("clear").executes(ctx -> {
                            clear();
                            return feedback(ctx.getSource(), "cleared");
                        }))
                        // 任意两个角点确定一个长方体。查询的是已收到的逐事件历史，
                        // 所以一次 /tick step 14 后也能看到中途伸出又收回的活塞。
                        .then(literal("inspect")
                                .then(argument("x1", IntegerArgumentType.integer())
                                .then(argument("y1", IntegerArgumentType.integer())
                                .then(argument("z1", IntegerArgumentType.integer())
                                .then(argument("x2", IntegerArgumentType.integer())
                                .then(argument("y2", IntegerArgumentType.integer())
                                .then(argument("z2", IntegerArgumentType.integer())
                                .executes(ctx -> inspect(ctx.getSource(),
                                        new BlockPos(getInteger(ctx, "x1"), getInteger(ctx, "y1"), getInteger(ctx, "z1")),
                                        new BlockPos(getInteger(ctx, "x2"), getInteger(ctx, "y2"), getInteger(ctx, "z2")))))))))))
                        .then(literal("config").executes(ctx -> {
                            if (!FabricLoader.getInstance().isModLoaded("malilib")) {
                                return feedback(ctx.getSource(), "malilib_required");
                            }
                            MasaConfigBridge.open(Minecraft.getInstance().screen);
                            return 1;
                        }))));
    }

    private static int feedback(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
                                String key, Object... args) {
        source.sendFeedback(Component.translatable("command.tickweave." + key, args));
        return 1;
    }

    private static void clear() {
        HISTORY.clear();
        selectedOffset = 0;
    }

    /**
     * 检查一个坐标范围在最近 40 个有记录游戏刻中的全部活塞事件与红石电平变化。
     * 游戏保存的方块状态只反映最后一刻；本方法读取网络轨迹，因此可以区分
     * “曾实际伸出后收回”“事件返回但状态未变”“根本没有伸出事件”等情况。
     * 明细限制为最后 48 条以免聊天栏被大型电路刷屏，汇总始终统计全部匹配项。
     */
    private static int inspect(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
                               BlockPos a, BlockPos b) {
        if (HISTORY.isEmpty()) return feedback(source, "inspect_empty");
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        List<InspectionLine> lines = new ArrayList<>();
        int extend = 0, noMove = 0, failedExtend = 0, retract = 0, wireChanges = 0, outputs = 0;
        for (Map.Entry<Long, TickTrace> tick : HISTORY.entrySet()) {
            TickAnalysis analysis = analyze(tick.getValue());
            for (TraceEntry event : tick.getValue().events) {
                if (!event.piston() || !inside(event.pos(), minX, maxX, minY, maxY, minZ, maxZ)) continue;
                if (!event.extendedBefore() && event.extendedAfter()) extend++;
                if (event.extendedBefore() && !event.extendedAfter()) retract++;
                if (event.action() == 0 && !event.succeeded()) failedExtend++;
                if (event.action() == 0 && event.succeeded()
                        && event.extendedBefore() == event.extendedAfter()) noMove++;
                if (analysis.outputExtensions.containsKey(event.sequence())) outputs++;
                lines.add(new InspectionLine(tick.getKey(), event.sequence(), Component.translatable(
                        "command.tickweave.inspect_piston", tick.getKey(), event.sequence(),
                        event.pos().getX(), event.pos().getY(), event.pos().getZ(), event.order(),
                        displayDepth(event.depth()), event.chain(), actionName(event),
                        event.succeeded() ? tr("inspect_success") : tr("inspect_cancelled"),
                        event.extendedBefore() ? 1 : 0, event.extendedAfter() ? 1 : 0,
                        redstonePositions(event), eventDetail(event, analysis),
                        traceOrigin(event), powerTrace(event))));
            }
            for (WireTrace wire : tick.getValue().wires) {
                if (wire.oldPower() == wire.newPower()
                        || !inside(wire.pos(), minX, maxX, minY, maxY, minZ, maxZ)) continue;
                wireChanges++;
                lines.add(new InspectionLine(tick.getKey(), wire.sequence(), Component.translatable(
                        "command.tickweave.inspect_wire", tick.getKey(), wire.sequence(),
                        wire.pos().getX(), wire.pos().getY(), wire.pos().getZ(),
                        wire.oldPower(), wire.newPower(), wire.source() == null
                                ? tr("unattributed_wire") : "D" + displayDepth(wire.depth()),
                        wire.source() == null ? tr("unknown_source")
                                : tr("source_event", formatPos(wire.source()), wire.sourceSequence()))));
            }
        }
        source.sendFeedback(Component.translatable("command.tickweave.inspect_summary",
                HISTORY.size(), extend, noMove, failedExtend, retract, wireChanges, outputs));
        if (lines.isEmpty()) return feedback(source, "inspect_none");
        lines.sort(Comparator.comparingLong(InspectionLine::tick).thenComparingInt(InspectionLine::sequence));
        int first = Math.max(0, lines.size() - MAX_INSPECT_LINES);
        if (first > 0) feedback(source, "inspect_omitted", first);
        for (int i = first; i < lines.size(); i++) source.sendFeedback(lines.get(i).message());
        return 1;
    }

    private static boolean inside(BlockPos pos, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    public static void setEnabled(boolean value) {
        ClientSettings.enabled = value;
        ClientSettings.save();
        if (FabricLoader.getInstance().isModLoaded("malilib")) MasaConfigBridge.syncEnabled();
    }

    public static void toggleEnabled() {
        setEnabled(!ClientSettings.enabled);
    }

    public static void previousTick() {
        selectedOffset = Math.clamp(selectedOffset + 1, 0, Math.max(0, HISTORY.size() - 1));
    }

    public static void nextTick() {
        selectedOffset = Math.max(0, selectedOffset - 1);
    }

    private static void accept(TracePacket packet) {
        if (!wasFrozen) {
            clear();
            wasFrozen = true;
        }
        // 一个游戏刻可能分多个网络包；按 tick 合并，而不是把每个分片当成新记录。
        boolean newTick = !HISTORY.containsKey(packet.tick());
        TickTrace trace = HISTORY.computeIfAbsent(packet.tick(), ignored -> new TickTrace());
        trace.events.addAll(packet.entries());
        trace.wires.addAll(packet.wires());
        while (HISTORY.size() > MAX_TICKS) HISTORY.pollFirstEntry();
        // 较旧游戏刻的分片不能抢走最新选择。
        if (newTick && packet.tick() == HISTORY.lastKey()) selectedOffset = 0;
    }

    private static Map.Entry<Long, TickTrace> selected() {
        // 按游戏刻排序而非包到达顺序；0 表示最新的有记录游戏刻。
        int i = 0;
        for (Map.Entry<Long, TickTrace> entry : HISTORY.descendingMap().entrySet()) {
            if (i++ == selectedOffset) return entry;
        }
        return null;
    }

    private static boolean shouldDraw() {
        Minecraft minecraft = Minecraft.getInstance();
        return ClientSettings.enabled && minecraft.level != null && minecraft.player != null && minecraft.level.tickRateManager().isFrozen();
    }

    private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!shouldDraw()) return;
        Minecraft minecraft = Minecraft.getInstance();
        Map.Entry<Long, TickTrace> selected = selected();
        TickTrace trace = selected == null ? new TickTrace() : selected.getValue();
        TickAnalysis analysis = analyze(trace);
        List<TraceEntry> entries = ClientSettings.showPistonEvents ? trace.events : List.of();
        int rows = Math.min(entries.size(), 12);
        int width = 380;
        boolean hasOverflow = entries.size() > rows;
        int height = 45 + rows * 11 + (hasOverflow ? 12 : 0);
        // GUI 使用逻辑像素。先平移再缩放，X/Y 配置是屏幕上的左上角位置。
        graphics.pose().pushMatrix();
        graphics.pose().translate(ClientSettings.hudX, ClientSettings.hudY);
        graphics.pose().scale((float) ClientSettings.hudScale, (float) ClientSettings.hudScale);
        graphics.fill(0, 0, width, height, ClientSettings.backgroundColor);
        graphics.text(minecraft.font, tr("title"), 6, 5, ClientSettings.titleColor, true);
        long wireChanges = ClientSettings.showWireUpdates
                ? trace.wires.stream().filter(wire -> wire.oldPower() != wire.newPower()).count() : 0;
        graphics.text(minecraft.font, selected == null ? tr("empty")
                : tr("summary", selected.getKey(), entries.stream().filter(TraceEntry::piston).count(),
                        wireChanges, selectedOffset + 1, HISTORY.size()),
                6, 17, ClientSettings.textColor, true);
        int maxDepth = entries.stream().mapToInt(event -> displayDepth(event.depth())).max().orElse(0);
        long chains = entries.stream().map(TraceEntry::chain).distinct().count();
        graphics.text(minecraft.font, tr("legend", chains, maxDepth), 6, 29, ClientSettings.mutedColor, false);
        for (int i = 0; i < rows; i++) {
            TraceEntry event = entries.get(i);
            String marker = eventDetail(event, analysis);
            String line = tr("event_row", event.piston() ? "#" + event.order() : "*", displayDepth(event.depth()),
                    marker, actionName(event), event.pos().getX(), event.pos().getY(), event.pos().getZ(),
                    eventStatus(event));
            graphics.text(minecraft.font, line, 6, 43 + i * 11, colorForDepth(event.depth()), false);
        }
        if (hasOverflow) graphics.text(minecraft.font,
                tr("overflow", entries.size() - rows),
                6, height - 15, ClientSettings.mutedColor, false);
        graphics.pose().popMatrix();
    }

    public static void emitWorldGizmos(double cameraX, double cameraY, double cameraZ) {
        if (!shouldDraw()) return;
        Map.Entry<Long, TickTrace> selected = selected();
        if (selected == null) return;
        TickAnalysis analysis = analyze(selected.getValue());
        // 同一方块在一刻内可能多次执行；逐行抬高标签，避免彼此遮挡。
        Map<BlockPos, Integer> rows = new HashMap<>();
        Map<Long, Integer> chainDepths = new HashMap<>();
        for (TraceEntry event : selected.getValue().events) {
            chainDepths.merge(event.chain(), displayDepth(event.depth()), Math::max);
        }
        if (ClientSettings.showPistonEvents) {
            for (TraceEntry event : selected.getValue().events) {
                BlockPos pos = event.pos();
                if (pos.distToCenterSqr(cameraX, cameraY, cameraZ)
                        > ClientSettings.renderDistance * ClientSettings.renderDistance) continue;
                int color = colorForDepth(event.depth());
                int row = rows.merge(pos, 1, Integer::sum) - 1;
                Vec3 labelPos = Vec3.atLowerCornerWithOffset(pos, 0.5, 1.35 + row * 0.65, 0.5);
                if (event.piston()) {
                    // 大字只表示本刻活塞的真实执行序号；下方小字解释因果深度。
                    Gizmos.billboardText("#" + event.order(), labelPos,
                            TextGizmo.Style.forColorAndCentered(color)
                                    .withScale(worldScale(ClientSettings.pistonNumberScale))).setAlwaysOnTop();
                }
                String detail = "D" + displayDepth(event.depth()) + eventDetail(event, analysis) + eventStatus(event);
                // 根事件只在链确实向下传播时显示链最高层，避免 D1 再写一次最高 D1。
                int chainMax = chainDepths.getOrDefault(event.chain(), 1);
                if (event.depth() == 0 && chainMax > 1) detail += tr("max_depth", chainMax);
                if (!event.piston()) detail = blockName(event.blockId()) + " " + detail;
                if (!detail.isBlank()) {
                    Gizmos.billboardText(detail, labelPos.add(0, event.piston() ? -0.36 : 0, 0),
                            TextGizmo.Style.forColorAndCentered(color)
                                    .withScale(worldScale(ClientSettings.detailScale))).setAlwaysOnTop();
                }
                if (event.parent() != null && !event.parent().equals(pos)) {
                    // 彩色箭头：处理父方块事件时产生了这个子事件。
                    Vec3 start = Vec3.atCenterOf(event.parent());
                    Vec3 end = Vec3.atCenterOf(pos);
                    Gizmos.arrow(start, end, color, 2.0f).setAlwaysOnTop();
                }
                if (event.triggeringWire() != null && !event.triggeringWire().equals(pos)) {
                    // 橙色箭头：入队时正在求值的红石粉，可辅助定位直接触发线。
                    Gizmos.arrow(Vec3.atCenterOf(event.triggeringWire()), Vec3.atCenterOf(pos),
                            ClientSettings.triggerArrowColor, 1.25f).setAlwaysOnTop();
                }
                if (analysis.outputExtensions.containsKey(event.sequence()) && event.facing() >= 0) {
                    // 把 0t 丢块的结果标在第二格红石块上，而非只标在活塞本体。
                    BlockPos outputPos = pos.relative(Direction.from3DDataValue(event.facing()), 2);
                    Gizmos.billboardText(tr("redstone_output_block"),
                            Vec3.atLowerCornerWithOffset(outputPos, 0.5, 1.1, 0.5),
                            TextGizmo.Style.forColorAndCentered(color)
                                    .withScale(worldScale(ClientSettings.detailScale))).setAlwaysOnTop();
                }
            }
        }
        if (ClientSettings.showWireUpdates) {
            // 一根线可能在同一刻被多条链多次检查，先按坐标汇总再绘制一次。
            Map<BlockPos, WireDisplay> wireDisplays = new HashMap<>();
            for (WireTrace wire : selected.getValue().wires) {
                wireDisplays.computeIfAbsent(wire.pos(), ignored -> new WireDisplay()).add(wire);
            }
            for (Map.Entry<BlockPos, WireDisplay> entry : wireDisplays.entrySet()) {
                BlockPos pos = entry.getKey();
                if (pos.distToCenterSqr(cameraX, cameraY, cameraZ)
                        > ClientSettings.renderDistance * ClientSettings.renderDistance) continue;
                WireDisplay wire = entry.getValue();
                String label = wire.label();
                int color = wire.depths.isEmpty() ? ClientSettings.mutedColor : colorForDepth(wire.depths.last() - 1);
                Vec3 labelPos = Vec3.atLowerCornerWithOffset(pos, 0.5, 0.38, 0.5);
                Gizmos.billboardText(label, labelPos,
                        TextGizmo.Style.forColorAndCentered(color)
                                .withScale(worldScale(ClientSettings.wireScale))).setAlwaysOnTop();
            }
        }
    }

    private static String eventDetail(TraceEntry event, TickAnalysis analysis) {
        StringBuilder detail = new StringBuilder();
        // 只有同刻确实处理过、parentSequence 精确指向本事件的子事件，
        // 才称为本事件“产生了下一层”。单纯成功入队但未处理另行标出。
        int children = analysis.children.getOrDefault(event.sequence(), 0);
        if (children > 0) detail.append(tr("caused_next_depth", displayDepth(event.depth() + 1), children));
        else if (event.produced() > 0) detail.append(tr("queued_unprocessed", event.produced()));
        if (event.duplicateAttempts() > 0) detail.append(tr("queue_deduped", event.duplicateAttempts()));
        if (event.redstoneFrontBefore() && !event.redstoneSecondBefore() && event.redstoneSecondAfter()) {
            detail.append(tr("redstone_pushed"));
        }
        TraceEntry extension = analysis.outputExtensions.get(event.sequence());
        if (extension != null) {
            // 同一游戏刻，同一黏性活塞先把红石块推向第二格，后以 action=2 收回并留下红石块。
            // 显示两次事件各自的 BED 层级。ID 2 只证明丢块收回，不能仅凭它
            // 判断这个事件来自用户所说的基准线或重置线。
            detail.append(tr("depth_used", displayDepth(extension.depth()), displayDepth(event.depth())));
        }
        if (event.piston() && event.action() == 0 && !event.succeeded()) {
            // 原版 triggerEvent 在执行时无电会拒绝伸出。这个判定必须以执行时为准；
            // 入队时有电并不保证排到该事件时仍有电。
            if (!event.blockMatched()) detail.append(tr("missing_piston"));
            else if (event.powerAtExecution() == 0) detail.append(tr("unpowered_at_execution"));
            else if (event.powerAtExecution() == 1) detail.append(tr("push_failed"));
        }
        return detail.toString();
    }

    private static String traceOrigin(TraceEntry event) {
        if (event.parentSequence() < 0 || event.parent() == null) return tr("root_event");
        return tr("parent_event", formatPos(event.parent()), event.parentSequence());
    }

    private static String powerTrace(TraceEntry event) {
        return tr("power_trace", powerValue(event.powerAtQueue()), powerValue(event.powerAtExecution()));
    }

    private static String powerValue(int value) {
        return value < 0 ? "?" : Integer.toString(value);
    }

    private static String formatPos(BlockPos pos) {
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }

    private static String redstonePositions(TraceEntry event) {
        if (!event.redstoneFrontBefore() && !event.redstoneSecondBefore()
                && !event.redstoneFrontAfter() && !event.redstoneSecondAfter()) return "";
        return "R" + (event.redstoneFrontBefore() ? 1 : 0) + (event.redstoneSecondBefore() ? 1 : 0)
                + "→" + (event.redstoneFrontAfter() ? 1 : 0) + (event.redstoneSecondAfter() ? 1 : 0);
    }

    private static TickAnalysis analyze(TickTrace trace) {
        Map<Integer, TraceEntry> bySequence = new HashMap<>();
        for (TraceEntry event : trace.events) bySequence.put(event.sequence(), event);
        Map<Integer, Integer> children = new HashMap<>();
        for (TraceEntry event : trace.events) {
            TraceEntry parent = bySequence.get(event.parentSequence());
            if (parent != null && parent.chain() == event.chain() && parent.depth() + 1 == event.depth()) {
                children.merge(parent.sequence(), 1, Integer::sum);
            }
        }
        List<TraceEntry> ordered = new ArrayList<>(trace.events);
        ordered.sort(Comparator.comparingInt(TraceEntry::sequence));
        Map<BlockPos, TraceEntry> recentExtension = new HashMap<>();
        Map<Integer, TraceEntry> outputExtensions = new HashMap<>();
        for (TraceEntry event : ordered) {
            if (!event.piston()) continue;
            if (event.action() == 0 && !event.extendedBefore() && event.extendedAfter()
                    && event.redstoneFrontBefore() && !event.redstoneSecondBefore() && event.redstoneSecondAfter()) {
                recentExtension.put(event.pos(), event);
            } else if (event.action() == 2 && event.succeeded() && event.extendedBefore()
                    && !event.extendedAfter() && event.redstoneSecondAfter()) {
                TraceEntry extension = recentExtension.remove(event.pos());
                if (extension != null) outputExtensions.put(event.sequence(), extension);
            } else if ((event.action() == 1 || event.action() == 2) && event.succeeded()) {
                recentExtension.remove(event.pos());
            }
        }
        return new TickAnalysis(children, outputExtensions);
    }

    private static String eventStatus(TraceEntry event) {
        if (!event.succeeded()) return tr("cancelled");
        // 原版已处理活塞事件但方块状态没变时，明确提醒玩家不能把它当作输出。
        if (event.piston() && event.action() == 0
                && event.extendedBefore() == event.extendedAfter()) return tr("no_state_change");
        return "";
    }

    private static float worldScale(double individualScale) {
        return (float) (ClientSettings.worldTextScale * individualScale);
    }

    private static int displayDepth(int zeroBasedDepth) {
        // 服务端根事件为 0；画面以 D1 起始，便于直接数出 D1–D4 四层。
        return zeroBasedDepth + 1;
    }

    private static String blockName(String blockId) {
        int separator = blockId.indexOf(':');
        if (separator < 0) return blockId;
        // 其他游戏语言中，原版方块名会被翻译为对应的第三种语言。
        // 为保持本模组“中文或英文”的约定，非中英语言改用英文资源 ID。
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        boolean supportedChinese = "zh_cn".equals(language) || "zh_tw".equals(language) || "zh_hk".equals(language);
        if (!supportedChinese && !"en_us".equals(language)) return blockId;
        String key = "block." + blockId.substring(0, separator) + "." + blockId.substring(separator + 1);
        return I18n.exists(key) ? I18n.get(key) : blockId;
    }

    private static String actionName(TraceEntry event) {
        if (!event.piston()) return blockName(event.blockId());
        return switch (event.action()) {
            case 0 -> tr("extend");
            case 1 -> tr("retract");
            case 2 -> tr("drop");
            default -> tr("unknown_action", event.action());
        };
    }

    private static String tr(String key, Object... args) {
        // I18n 使用客户端当前语言。模组只提供英文和中文，其余语言回退到 en_us。
        return I18n.get("text.tickweave." + key, args);
    }

    private static final class TickTrace {
        private final List<TraceEntry> events = new ArrayList<>();
        private final List<WireTrace> wires = new ArrayList<>();
    }

    /** 从已处理事件重建确证的产深边与同刻红石块输出；每次分析单独处理一个游戏刻。 */
    private record TickAnalysis(Map<Integer, Integer> children, Map<Integer, TraceEntry> outputExtensions) { }

    /** 同一刻内按服务端共用的 sequence 排序，重建事件与红石变化的真实先后。 */
    private record InspectionLine(long tick, int sequence, Component message) { }

    private static final class WireDisplay {
        private final TreeSet<Integer> depths = new TreeSet<>();
        private boolean base;
        private int power;

        private void add(WireTrace wire) {
            // source == null 只说明无法归属到父事件，不能据此认定它是“重置线”。
            if (wire.source() == null) base = true;
            else depths.add(displayDepth(wire.depth()));
            power = wire.newPower();
        }

        private String label() {
            StringBuilder text = new StringBuilder();
            if (base) text.append(tr("unattributed_wire")).append(' ');
            if (!depths.isEmpty()) {
                text.append("D");
                for (int depth : depths) {
                    if (depth != depths.first()) text.append(',');
                    text.append(depth);
                }
                text.append(' ');
            }
            return text.append('P').append(power).toString();
        }
    }

    private static int colorForDepth(int depth) {
        return ClientSettings.depthColors[Math.floorMod(depth, ClientSettings.depthColors.length)];
    }
}

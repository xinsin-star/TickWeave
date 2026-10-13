# 刻络 · TickWeave

<img src="src/main/resources/assets/tickweave/icon.png" alt="TickWeave 图标" width="128" align="right">

TickWeave 是一个用于观察 Minecraft 红石**微时序**的 Fabric 模组。在游戏刻冻结后逐刻运行电路，它会记录服务端的活塞方块事件和红石粉求值，并在客户端显示执行顺序、事件深度与传播关系，方便分析 BED 编解码器等对更新顺序敏感的装置。

## 功能

- **活塞执行顺序**：用 `#1`、`#2`……标出同一游戏刻内活塞事件实际执行的先后。
- **方块事件深度**：用 `D1`、`D2`……标出方块事件的因果层级，并用箭头连接可确认的父子事件。
- **红石线观察**：显示红石粉在当前刻被观测到的电平、关联深度和直接触发关系。
- **活塞状态排查**：记录事件入队与执行时的供电情况，区分实际伸出、收回、状态未变和取消。
- **0t 红石块输出**：识别同刻伸出推红石块、随后丢块收回的组合，并标出第二格的物理输出。
- **历史查询**：浏览最近 40 个有记录的游戏刻，或用坐标范围查询逐事件明细。
- **可配置界面**：分别控制活塞与红石线的显示，调整世界文字、HUD、颜色、距离和快捷键。

画面中的 `Dn` 是**方块事件因果深度**，不代表红石粉跨游戏刻保存了这个数字，也不包含原版引擎中所有邻居通知。

## 安装

1. 从 GitHub Actions 或 Release 下载与你的 Minecraft 版本对应的 `tickweave-*.jar`。`-sources.jar` 是源码包，不能作为游戏模组安装。
2. **单人游戏**：将 TickWeave、Fabric API 和 MaLiLib 放入客户端 `mods` 文件夹。
3. **多人游戏**：客户端按上述方式安装；服务器安装相同版本的 TickWeave 与 Fabric API。MaLiLib 和 Mod Menu 只需要安装在客户端。
4. 启动游戏，确认模组列表中出现 **TickWeave**。

## 使用方法

先在有 `/tick` 指令权限的世界中开启显示并冻结游戏刻：

```text
/tickweave on
/tick freeze
```

触发红石信号激活，然后逐刻执行：

```text
/tick step 1
```

观察活塞上方的编号与深度标签，以及红石线上的标签。若信号经过侦测器或其他延迟元件，可能需要继续执行几次 `/tick step 1`。

### 客户端指令

- `/tickweave on`、`/tickweave off`：开启或关闭可视化。
- `/tickweave prev`、`/tickweave next`：切换到更早或更新的有记录游戏刻。
- `/tickweave clear`：清空本次客户端记录。
- `/tickweave config`：打开 MaLiLib 设置页面。
- `/tickweave inspect <x1> <y1> <z1> <x2> <y2> <z2>`：查询两个角点围成的长方体范围。

例如：

```text
/tickweave clear
/tick step 1
/tickweave inspect 0 64 0 10 70 10
```

`inspect` 会汇总范围内实际伸出、收回、取消、红石电平变化和 0t 红石块输出，并按游戏刻和同刻序号显示最后 48 条明细。中间短暂伸出又收回的动作，请用 `prev` 浏览或通过 `inspect` 查询。坐标范围过大时可以缩小查询范围，汇总仍会统计全部匹配记录。

仅在 tick 冻结时采集并绘制轨迹。客户端接收同维度、距玩家 96 格内的记录；世界标签还受配置中的绘制距离限制。

## 如何理解标签

- 活塞大字 `#3`：该活塞事件是当前游戏刻第 3 个**执行**的活塞事件。
- `D2 · 引发 D3`：当前事件属于第二层，执行过程中产生了下一层子事件。不是一个活塞同时拥有两个深度，也不是数值相加。根事件显示为 `D1`。
- 红石粉上的 `D2,3 P15`：这一位置在当前刻关联到第二、第三层事件，最后观测信号为 15。`R` 表示没有可归属的父方块事件，不自动代表基准线或重置线。
- `E0→E1`、`E1→E0`：活塞 `extended` 状态分别变成伸出、拉回。`E0→E0` 表示该事件没有造成实际伸出。
- “入队电1→执行电0”：事件排队时有电，但轮到执行时失电；这可以解释部分伸出事件为什么被取消。
- `R10→01`：活塞前方第一、第二格的红石块占位从 `10` 变为 `01`；移动中的红石块也按占位计算。

只有同一游戏刻内同时观测到伸出推红石块与后续丢块收回时，才会标记 **0t 红石块输出**。该标签描述物理输出，不表示事件深度被“消耗一层”。

## 配置与语言

输入 `/tickweave config`，或通过 Mod Menu 的配置按钮打开 MaLiLib 页面。客户端设置保存在 `config/tickweave.json`，修改大小与颜色后可立即预览。

- **显示**：总开关、活塞事件和红石线分别控制。
- **文字**：世界标签总倍率，以及活塞序号、事件详情和红石线的独立大小。
- **HUD**：整体倍率与屏幕 X/Y 位置。
- **颜色**：HUD 文字与背景、触发箭头，以及 D1–D6 循环颜色。
- **距离**：世界标签绘制距离，范围 8–96 格。
- **快捷键**：开关显示、上一刻、下一刻、打开设置；默认不绑定。

界面跟随游戏语言。提供简体中文、繁体中文和英文；未提供翻译的语言回退到英文。颜色项修改的是文字或箭头的颜色，字形由 Minecraft 当前资源包提供。

## 开发与构建

在当前构建目标下安装 JDK 25，然后在项目根目录执行：

```sh
./gradlew build
```

Windows 使用 `gradlew.bat build`。可安装的 JAR 位于 `build/libs/`。首次构建需联网下载 Gradle、Minecraft、Fabric 与可选界面库的编译依赖。在 IntelliJ IDEA 中应以**项目根目录的 `build.gradle`** 导入 Gradle 项目，并将 Gradle JVM 设置为 JDK 25。

服务端的方块事件采集位于 [`ServerLevelMixin`](src/main/java/top/xinsin/tickweave/mixin/ServerLevelMixin.java)，红石粉求值上下文位于 [`WireContext`](src/main/java/top/xinsin/tickweave/server/WireContext.java)，网络分片由 [`TraceBroadcaster`](src/main/java/top/xinsin/tickweave/server/TraceBroadcaster.java) 发送，客户端展示位于 [`TickWeaveClient`](src/main/java/top/xinsin/tickweave/client/TickWeaveClient.java)。配置由 [`ClientSettings`](src/main/java/top/xinsin/tickweave/client/ClientSettings.java) 保存，MaLiLib 页面由 [`MasaConfigBridge`](src/main/java/top/xinsin/tickweave/client/MasaConfigBridge.java) 提供。

[GitHub Actions](.github/workflows/build.yml) 会在推送到 `main`、提交 Pull Request 和手动触发时检查提交标题并构建 JAR。CI 通过表示提交格式与编译检查通过；游戏内效果仍需实际装置验证。

## 贡献与提交格式

**每条提交标题必须采用 `表情 Type: 简短说明` 格式。** 表情、英文类型的大小写、冒号和冒号后的一个空格都必须与下面一致；说明不能为空。允许的前缀：

- `➕ Feat: ` 新功能
- `🚧 Fix: ` 缺陷修复
- `📝 Docs: ` 文档修改
- `♻️ Refactor: ` 不改变行为的重构
- `🧪 Test: ` 测试
- `🔧 Chore: ` 日常维护
- `🏗️ Build: ` 构建和依赖
- `👷 CI: ` 持续集成
- `⚡ Perf: ` 性能优化

正确示例：

```text
➕ Feat: 添加红石线深度标签
🚧 Fix: 修复活塞事件顺序显示
📝 Docs: 更新安装与使用说明
```

`feat: ...`、`Fix: ...`、`➕ feat: ...` 等格式都不符合要求。CI 会检查推送或 Pull Request 中的**每一条提交标题**。合并 Pull Request 时请让最终提交标题也符合该规则；自动生成的普通 merge commit 标题可能无法通过检查。

## 许可协议

本项目采用 **MIT License**。完整条款见 [LICENSE](LICENSE)。

# EMC Assistant（EMCA）

**Forge 1.20.1 / ProjectE 附属** —— 把其他 mod 的「加工配方」翻译给等价交换（ProjectE），并预设原材料种子 EMC，让机器/仪式产物的 EMC 由 ProjectE 图算法**自己算出来**。

> 与「等价兼容（ProjectE Integration）」的哲学区别：它把兼容 mod 的**所有物品**预写 EMC（写答案）；我们只写**种子 + 规则**（原材料 EMC 种子值 + 配方转换边），产物价值让图算法动态生长——原料改价全链自动传导，配方链完整，不用手工维护每个值。

## 文档导航

| 文档 | 职责 | 状态 |
|---|---|---|
| **README.md**（本文件） | 对外门面：定位 / 用法 / 构建 / 生态分工 / 来源声明 | ✅ 现行 |
| **[RECIPE-TRANSLATOR-DESIGN.md](RECIPE-TRANSLATOR-DESIGN.md)** | **现行主功能设计**：M1–M3.5 演进史 + §13 现行技术路线 | ✅ 现行 |
| [DESIGN.md](DESIGN.md) | 历史基线设计（2026-07）：原始"扫描+标注工具"架构，其五道判断逻辑 = 种子预设思想来源 | ⚠️ 已过时（被 M3 产品模式取代） |
| [RECIPE-INTEGRATION-RESEARCH.md](RECIPE-INTEGRATION-RESEARCH.md) | 调研档案：配方翻译可行性/三路线 | 📄 设计依据 |
| [GITHUB-ECOSYSTEM-RESEARCH.md](GITHUB-ECOSYSTEM-RESEARCH.md) | 调研档案：GitHub 生态/API/保底机制 | 📄 设计依据 |

建议阅读顺序：本文件 → RECIPE-TRANSLATOR-DESIGN.md §13（现行路线）→ 需要追溯决策史再看 DESIGN.md / 调研文档。

## 核心机制

1. **配方翻译（RecipeTypeMapper）**：6 个 mod 的机器/仪式配方在 ProjectE mapping 阶段被逐条翻译成转换边（`addConversion`），产物 EMC = Σ材料 ÷ 产出数，由图算法传播计算。
   - 支持：`create`、`mekanism`、`thermal`、`occultism:ritual`（仪式）、`bloodmagic:altar`（祭坛）、`productivebees`
   - `@RecipeTypeMapper(requiredMods = ...)` → **mod 不在包内自动不注册**（条件激活，零代码判断）
   - 仪式配方的灵魂/魔力/献祭成本忽略，只取 `ingredients → result`（ProjectE 只认物品输入输出）

2. **原材料种子 EMC（@EMCMapper）**：给"图算法算不出的根节点"预设种子值：
   - 数据参考自 MIT 开源的 ProjectE Integration（TagnumElite）种子表（`raw_emc.json`，作者校准值）
   - 16 种原版染料手动钉值（染料互染/分解配方成环 → ProjectE 环检测归零，必须种子兜住）
   - 整体 try-catch 隔离，失败只记日志，**绝不影响服务器启动**

## 组件

| 组件 | 说明 |
|---|---|
| **P1 mod（Java）** | 主产品：`RecipeTypeMapper` ×6 + 原料预设 mapper + `/emca` 调试命令 |
| **P2 工具（Python，可选）** | 离线分析：导入 P1 快照、配方缺口报告（`gap_report`）、EMC 自动标注 |

## 用法（玩家/整合包作者）

1. 安装 [ProjectE](https://www.curseforge.com/minecraft/mc-mods/projecte) 1.20.1 与本 mod 的 jar
2. 进游戏即生效（静默，无任何弹窗/广播）
3. 调试命令（仅 op，供整合包作者排查缺口/环）：
   - `/emca status` — 模式与扫描摘要
   - `/emca scan` / `/emca rescan` — 手动扫描/重扫
   - `/emca translate` — 生成转换边 datapack（开发/调试用）
   - `/emca missing` — 输出无 EMC 物品报告到 `logs/emc_assistant/missing_report.txt`

## 构建

- 需要：JDK 17、Gradle 8.5
- 依赖：`compileOnly` ProjectE 1.20.1 jar（本仓库不携带二进制，请自行放置到 `libs/projecte-1.20.1.jar`，或改 build.gradle 为 Maven 依赖）
- 命令：`gradle build`，产物在 `build/libs/`

## 生态分工（不重复造轮子）

| 负责方 | 覆盖 |
|---|---|
| **本 mod** | Create / Mekanism / Thermal / Occultism / Blood Magic / Productive Bees + 原料种子预设 |
| [GTToolMapper](https://www.curseforge.com/minecraft/mc-mods/gttoolmapper) | GTCEu 机器配方（内部注册表，不走 RecipeManager） |
| ProjectE Integration（等价兼容） | Botania / Ars Nouveau / AE2 / Avaritia / Chipped 等 |

## 已知限制 / 路线图

- `mekanism:mek_data` 嵌套配方、`forge:conditional` 暂跳过（M3 适配器）
- 通配配方、蜜蜂实体类（breeding/conversion）跳过（输入输出非物品）
- tag 输入取第一个成员（保守近似）
- 第二梯队适配器（enderio / chemlib / rechiseled / mysticalagriculture / railcraft...）按缺口报告数字排队中
- 保底机制（min EMC floor）设计为 config 开关，未实现

## 数据来源声明

- `src/main/resources/data/emcassistant/raw_emc.json` 的原料种子值**数据参考自 [ProjectE Integration](https://github.com/TagnumElite/ProjectE-Integration)（作者 TagnumElite，**MIT License**）1.20.1 的 `pe_custom_conversions` values.before 表**，仅作事实性数值引用，按 MIT 协议要求保留来源声明。

## License

[MIT](LICENSE)

# RecipeTranslator 设计 v1（加工配方 → ProjectE 转换边）

> 2026-08-05 · EMC Assistant 主功能设计
> 上游调研：`RECIPE-INTEGRATION-RESEARCH.md`（可行性/三路线）+ `GITHUB-ECOSYSTEM-RESEARCH.md`（生态/API/保底分析）。
> 用户拍板：①保底机制**本期不做**，设计成 config 开关预留；②**先做主要的加工配方翻译**。

> **📌 文档状态（2026-08-06 更新）**：本文件保留完整决策演进史；标注「已废除」的方案不再实施。
> **现行技术路线 = §12/§13 的 M3 产品模式**：装 jar 即用（6 个 RecipeTypeMapper 翻译配方 + RawMaterialEmcMapper 快速路径种子表），M1–M3.5 已全部实现并交付，见 §13。
> **下一迭代（2026-08-10 更新）**：§15 = 价值传播引擎 v2 设计（min-迭代 + 种子层 + 规则集），**设计定稿未实现**，待 §15.10 四项确认后开工。
> **文档关系**：[DESIGN.md](DESIGN.md) = 项目历史基线设计（原始"扫描+标注工具"架构，五道判断逻辑为种子预设思想来源，已过时）；[README.md](README.md) = 对外门面（含文档导航）。

## 1. 目标与边界

- **目标**：把整合包里 ProjectE 默认不认识的加工配方（机器/仪式 recipeType）翻译成 ProjectE 的转换边（`customConversions`），使机器产物/仪式产物获得动态计算的 EMC。
- **非目标（本期不做）**：保底机制（config 开关预留）、加工溢价系数（用户已否决）、GT 适配（不在 ATM10，蹭 GTToolMapper）、玩家 EMC 自动化、万物等价大目标。

## 2. 位置决策：P1 快照增强 + P2 翻译器（理由）

| 方案 | 评估 | 状态 |
|---|---|---|
| ~~R1a 工具离线扫 mods jar~~ | ❌ 拿不到运行时配方；tag 跨 mod 展开难；版本耦合高 | **已废除** |
| ~~R2 mod 内直接 EMCApi.setValue~~ | 可行（API 已实证）但把翻译逻辑锁死在 Java mod 里，迭代慢、跟 ProjectE 版本走 | **已废除** |
| ~~R1b P1 导配方快照 + P2 翻译器~~ | ✅ P1 游戏内导出**完整配方快照**（RecipeManager 全量 + recipeType 分类）→ P2 纯数据翻译 → 生成 datapack JSON。曾选定 | **已废除**（被 §11 翻译器内置 mod 取代 → 再升级为 §12 产品模式） |
| **R3 现行：M3 产品模式（§12/§13）** | ✅ 装 jar 即用：`@RecipeTypeMapper(requiredMods=...)` 启动自动注册 → `handleRecipe` 逐条 `addConversion` 喂边（加工方式）+ `@EMCMapper` `setValueBefore` 设原料种子值。**无需扫描/生成/命令，与 Integration 天然排重** | ✅ **实施中（已交付）** |

管线（现行）：`装 jar → 进游戏 → ProjectE mapping 阶段自动发现 mapper → 转换边 + 种子值入图算法 → EMC 自动算好`。（早期管线 `P1 扫描 → snapshot → P2 翻译 → datapack → reloademc` 已作废，P2 降级为可选离线分析工具。）

## 3. P1 增强：snapshot schema 扩展

### 3.1 新增 recipes 表（SQLite）
```sql
CREATE TABLE recipes (
  recipe_id     TEXT PRIMARY KEY,   -- namespace:path
  recipe_type   TEXT NOT NULL,      -- "minecraft:crafting_shaped" / "create:crushing" / "goety:ritual" ...
  mod_id        TEXT NOT NULL,      -- 配方来源 mod（from type namespace）
  ingredients   TEXT NOT NULL,      -- JSON: [{item|tag 展开后 items:[...], count}]
  results       TEXT NOT NULL,      -- JSON: [{item, count}]
  special_cost  TEXT,               -- JSON: 非物品成本（goety soulCost/duration 等，翻译时忽略，仅记录）
  projecte_sees INTEGER,            -- 1=ProjectE 默认 mapper 已覆盖 / 0=未覆盖 / -1=未知
);
```
### 3.2 新增 recipe_types 统计（进 snapshot 报告）
- 每个 recipeType 的出现次数 + 涉及物品数 → 直接产出**配方缺口报告**基础。
- `projecte_sees` 判定：vanilla crafting/smelting 及其 mod 扩展（ProjectE `VanillaRecipeTypeMapper`/`FallbackRecipeTypeMapper` 覆盖的）标 1；其余标 0 待翻译。

## 4. P2 RecipeTranslator 模块

```
输入：snapshot（recipes + items/emc 现状）+ 配置
├─ ① 配方分类：按 recipeType → vanilla跳过 / 已适配 / 未适配(缺口报告)
├─ ② 适配器（注册表，条件激活）——按 ATM9（测试包）实测在包内收窄 + **与 ProjectE Integration 排重**：
│    create:*（crushing|milling|mixing|pressing|compacting...） ✅ 在包，Integration 未覆盖 → 我们做
│    mekanism:*（metallurgic_infusing|crushing|enriching|combining...） ✅ 在包，Integration 未覆盖 → 我们做
│    thermal:*（pulverizer|induction_smelter|centrifuge...） ✅ 全系列在包，Integration 未覆盖 → 我们做
│    occultism:ritual（仪式合成，替代 Goety） ✅ 在包，Integration 未覆盖 → 我们做
│    bloodmagic:*（血祭坛/仪式） ✅ 在包，⚠️ **Integration 已有 BMBloodAltarMapper 等** → 排重：装 Integration 则跳过，未装则我们做
│    productivebees:*（蜜蜂产物加工） ✅ 在包，Integration 未覆盖 → 我们做
│    （Goety / Resourceful Bees / GT 不在 ATM9 → 适配器保留但条件不激活）
│    （Botania/ArsNouveau/Alchemistry/AE2/Avaritia = Integration 已覆盖区 → 我们不做）
├─ ③ 转换边生成：每配方 → {count: results.count, output, ingredients}
├─ ④ 环自检：离线图检测（先于 ProjectE 发现环，防 EMC 归零连锁）
├─ ⑤ 0 传染预演：离线复刻 min 图算法（或 ProjectE DumpToFileCollector 输出）→ 标 0 传染源+受害链
├─ ⑥ 保底预留（config 开关，默认 OFF）：
│    [EconomySafety] enableMinEmcFloor=false  minEmcFloor=1
│    开启后：<floor 物品 → setValueAfter(floor)，白名单排除可自动化放大链
├─ ⑦ 生成 pe_custom_conversions JSON（data/emc_assistant/pe_custom_conversions/machines.json，groups 按 mod 分组）+ 缺口报告
输出：整合包 datapack JSON + 报告
```

### 翻译规则（用户已拍板）
- **只取 `ingredients → result`** 为转换边，1:1 材料守恒，无系数。
- 仪式配方的 `soulCost`/`duration`/`entity_to_sacrifice`/`activation_item`/`craftType` 结构要求 → **忽略**（进 `special_cost` 仅记录，不上转换边）。
- tag 输入：**可保留为 `#tag` 直接写进转换边**（1.20.1 ProjectE 原生支持，有 TagMapper）；若展开则展开后指向不存在物品 → 告警并跳过该边（防静默失效）。两者策略 M3 定稿。

## 5. 里程碑（实际进度，2026-08-06 更新）

| 里程碑 | 内容 | 状态 |
|---|---|---|
| **M1** | P1 增强：recipes 快照导出 + recipeType 分类 + projecte_sees 判定 + P2 recipes 表/gap_report | ✅ 已完成（P1 快照扩展 + P2 缺口报告） |
| **M1.5** | 游戏内消息栏反馈 + `/emca status/rescan` 命令 | ✅ 已完成 |
| **M2** | 翻译器内置 mod + 开发模式开关（`/emca mode on/off`）+ `/emca translate` 生成 datapack | ✅ 已完成（dev 命令保留，仅调试用） |
| **M2.1** | Integration 排重（`ModList.isLoaded` + 11 mod 黑名单）+ 每 mod 5000 边限流 | ✅ 已完成 |
| **M3** | 产品模式：6 个 RecipeTypeMapper + 原料预设 `@EMCMapper` + ProjectE 编译依赖 + 即用 jar | ✅ 已完成（dist/EMCAssistant.jar） |
| **M3.1–M3.5** | 闭环种子 → 逆向表内置 → 染料钉值 → 快照化防御 → **零干扰快速路径** | ✅ 已完成（见 §13） |
| **M4** | 安全自检：环检测 + 0 传染预演（离线） | ⏳ 部分（翻译器逐条容错已内置；完整预演未做） |
| **M5（预留）** | 保底开关实现（enableMinEmcFloor） | ⏳ config 开关预留，未实现（用户拍板本期不做） |

## 6. 依赖与风险

- ✅ **已实证：1.20.1 自定义转换 = datapack `data/<domain>/pe_custom_conversions/*.json`**（不是 config 目录）。格式模板 = ProjectE jar 自带 `data/projecte/pe_custom_conversions/defaults.json` + `metals.json`；写法示例 = ProjectE Integration 的 38 个 mod JSON（见 §9）。
- `/projecte reloademc` 热加载（M2 验证；不行则写 datapack 后重启一次）。
- P1 快照的 recipeType 覆盖度（mod 配方若不走 RecipeManager 则快照拿不到 → 缺口报告标"不可达"）。
- 适配器清单已按 ATM9 实证收窄 + 与 ProjectE Integration 排重（§4 ②）。

## 7. 待办流转（2026-08-06 更新：M1–M3.5 已完成）

- [x] 拉 ATM9 modlist：**测试包拍板 = ATM9**（1.20.1 Forge，与用户环境同栈；ATM10 是 1.21.1 NeoForge 弃用）
- [x] ⚠️ **ATM9 原包无 ProjectE** → 测试环境需加装 ProjectE 1.20.1（+ 我们的 P1 mod）
- [x] **配方形态实证（解包 ATM9 实际 jar）**：六候选 mod 配方全部是标准 datapack recipe JSON（`data/<mod>/recipes/`）→ P1 RecipeManager 可全量读到
- [x] **1.20.1 输出格式实证**：pe_custom_conversions datapack 格式（ProjectE jar 自带 defaults/metals.json + Integration 38 个示例）→ 不再依赖 example.json
- [x] **对标发现：ProjectE Integration（等价兼容）7.2.5** = 1.20.1 现役，半静态钉值 + RecipeTypeMapper；已覆盖 BM/Botania/Ars/Alchemistry/AE2/Avaritia → **我们的差异化 = Create/Mekanism/Thermal/Occultism/ProductiveBees（其空白区）**
- [x] **M1**：P1 snapshot schema + 导出逻辑 + 配方缺口报告首个产出
- [x] **M2/M2.1**：翻译器内置 + dev 模式 + Integration 排重 + 限流
- [x] **M3**：6 个 RecipeTypeMapper + 原料预设 + 即用 jar（§12.4 已勾选）
- [x] **M3.1–M3.5**：闭环种子 → 逆向表 → 染料 → 快照化 → 快速路径（§13）
- [ ] **下一步行动计划（2026-08-06 定优先级：先稳定性，后覆盖面）**

  **阶段 1：稳定性验收（最高优先级，必须先做）**
  - [ ] S1 装回最新快速路径 jar → 进旧存档 + 建新存档，确认**不崩**（CME / tombstone NPE 是否根治）
  - [ ] S2 `/emca missing` 出新报告（对比 31644，验证快速路径版实际效果）
  - [ ] S3 抽查关键 EMC：染料 = 32？Create crushing / Mekanism enriching 产物有值？（翻译 + 种子生效）
  - 通过标准：装 jar 进存档不崩 + 目标 mod 产物有 EMC

  **阶段 2：缺口收敛（稳定性通过后才做，按性价比排）**
  - [ ] ① 装 GTToolMapper + ProjectE Integration（白捡 ~1.6 万：GT 8714 + chipped 6151 等，用户侧零成本）
  - [ ] ② 恢复闭环种子为 config 开关（默认关，遍历版延迟/降级，目标 mod 缺口再降几百）
  - [ ] ③ 第二梯队适配器（enderio → chemlib → rechiseled → mysticalagriculture → railcraft，按新报告数字排）
  - [ ] ④ M4 环自检离线预演（安全网，可随时穿插）

  **决策原则**：稳定性 > 覆盖面。任何新适配器上线前，必须过"装 jar 进存档不崩"这关。

## 8. ATM9 配方形态实证（2026-08-05 解包 jar，第一手）

| mod | recipe JSON 数 | 关键 recipeType（翻译目标） |
|---|---|---|
| Occultism 1.151.0 | 783 | **occultism:ritual(66)** 仪式、crushing(180)、miner(86)、spirit_fire(13)、spirit_trade(3) |
| Blood Magic 3.3.3 | 590 | **bloodmagic:altar(23)** 祭坛、alchemytable(126)、soulforge(88)、arc(53)、array(25)、meteor(6)、flask_potion*(105) |
| Create 6.0.6 | 2709 | milling(208)、crushing(150)、deploying(112)、splashing(53)、pressing(39)、cutting(30)、haunting(22)、filling(19)、mixing(14)、item_application(8) |
| Mekanism 10.4.15 | 4223 | sawing(1804)、painting(976)、crushing(311)、enriching(251)、pigment_extracting(206)、injecting(76)、combining(66)、metallurgic_infusing(33)、purifying(28)、dissolution(23)、nucleosynthesizing(20) |
| Thermal 11.0.x | 499 | press(155)、insolator(63)、centrifuge(59)、pulverizer(57)、smelter(42)、bottler(23)、crucible(14)、sawmill(11)、crystallizer(9)、chiller(7) |
| Productive Bees 12.6.0 | 2275 | centrifuge(400)、advanced_beehive(387)、block_conversion(28)；**bee_breeding(101)/bee_conversion(114)/bee_spawning(35)=实体类，跳过** |

**Occultism ritual 样本结构**（data/occultism/recipes/ritual/craft_dimensional_matrix.json）：
```json
{"type":"occultism:ritual","ritual_type":"occultism:craft_with_spirit_name",
 "activation_item":{"item":"occultism:book_of_binding_bound_djinni"},
 "pentacle_id":"occultism:craft_djinni","duration":240,
 "ritual_dummy":{"item":"occultism:ritual_dummy/craft_dimensional_matrix"},
 "ingredients":[{...tag...}...],"result":{...}}
```
→ 翻译只取 `ingredients → result`；ritual_dummy/pentacle/duration/activation_item 忽略。

### 8.1 已知坑（翻译器必须处理）
1. **forge:conditional 嵌套**（Create/PB 等 jar 内大量出现）→ 翻译器先解包，取内层真 type。
2. **mekanism:mek_data 复合配方**（85 条）→ 内含多子配方/条件，需递归解析。
3. **蜜蜂实体类配方**（breeding/conversion/spawning）→ 输入输出是生物实体不是物品，**跳过**；只翻译物品类（centrifuge/advanced_beehive）。
4. **catalyst 类**（thermal:*_catalyst）→ 非产出转换边，跳过。
5. **occultism ritual_dummy 中间产物** → dummy 物品本身有配方、产物又被仪式引用 → **环风险点**，翻译器环自检重点盯。
6. **规模**：六 mod ≈ 1.1 万条 recipe JSON，转换边量级 ~1 万条，ProjectE 图算法可承受（本来就在处理整合包全量 crafting）。

## 9. 1.20.1 输出格式实证 + ProjectE Integration 对标（2026-08-05，解包 jar）

### 9.1 pe_custom_conversions 格式（1.20.1 = datapack，非 config 目录）
- 位置：`data/<domain>/pe_custom_conversions/*.json`（放整合包 datapack 或 mod jar 内）。
- 结构（ProjectE 自带 `defaults.json`/`metals.json` 实证）：
```json
{
  "comment": "…",
  "groups": { "<组名>": { "comment": "…", "conversions": [
    { "ingredients": ["minecraft:iron_helmet"], "output": "minecraft:chainmail_helmet" }
  ]}},
  "values": {
    "before": { "#forge:gems/amethyst": 32, "#forge:ingots/iron": 256 },
    "conversion": [
      { "count": 2, "ingredients": { "#forge:ingots/iron": 8 }, "output": "#forge:ingots/gold", "propagateTags": true }
    ]
  }
}
```
- **物品标识**：`mod:id`（注册名）；**tag 标识**：`#forge:xxx`（# 前缀，ProjectE 原生支持，TagMapper 处理 tag↔物品 双向）。
- **ingredients**：数组 = 每项数量 1；对象 = `{item-or-tag: count}`。
- **count** = 输出数量（默认 1）；**propagateTags** = 输出为 tag 时传播到所有成员。
- **values.before** = 固定值（图算法前，≈setValueBefore）；values.conversion = 优先级转换边（M2 若需保底 setValueAfter 对应 values.after，需再核 FixedValues）。

### 9.2 ProjectE Integration（等价兼容）7.2.5 技术解剖（1.20.1 现役对标）
- 双机制：① **38 个 mod 的 `data/<mod>/pe_custom_conversions/<mod>_default.json`**（静态钉值 values.before + 转换边 values.conversion，如 ae2_default.json 给 certus quartz 钉 256）；② **Java RecipeTypeMapper 类**（BloodMagicAddon$BMBloodAltarMapper / BMAlchemyArrayMapper / BotaniaAddon$* / ArsNouveauAddon$* / AlchemistryAddon$* / AEInscriberMapper / Avaritia Compressor...）+ CraftTweaker 桥（CrTConversionEMCMapper）。
- **已覆盖**：BloodMagic / Botania / Ars Nouveau / Alchemistry / AE2 / Avaritia / Ice and Fire / Farmers Delight / Chipped / Alex's Caves / Blue Skies 等。
- **未覆盖（我们的差异化空位）**：**Create / Mekanism / Thermal / Occultism / Productive Bees** —— 与 ATM9 适配器清单交集正好 = 我们首期要做的。
- **排重规则**：装 Integration 则其覆盖的 recipeType 跳过；BM 适配器仅在未装 Integration 时激活。
- 参考：Integration 的 pe_custom_conversions JSON 可直接抄格式；其 RecipeTypeMapper class 可反编译（CFR）作 M3 Java 适配写法参考。

## 10. M1 细化设计（2026-08-05，基于现有代码侦查）

现有代码（已读）：P1 `RegistryScanner.java`（Forge 1.20.1-47.3.0，`ServerStartingEvent` 触发，遍历 `ForgeRegistries.ITEMS` + `recipeManager.getRecipes()`，ProjectE 反射走 `IEMCProxy.INSTANCE.hasValue/getValue`，输出 `.emc_assistant/items_snapshot.json`）；P2 `emc_assistant.py`（SQLite：mods/items/snapshots/snapshot_items 四表，`EMCClient.import_snapshot/auto/stats`）+ `menu.py`（`import_and_prep` 调 `import_snapshot`）。

### 10.1 P1 改动（外科手术，最小侵入）
- 新增 `gatherRecipeDetails(RecipeManager, server)`：遍历 `recipeManager.getRecipes()`，每条导出：
  `{id, type, mod(type 前缀), covered(0/1), ingredients:[展开物品 id 或 "*" 通配], output:{item,count}}`
- `covered` 粗判：vanilla 类型集合（crafting_shaped/shapeless/smelting/blasting/smoking/campfire_cooking/stonecutting/smithing_transform/smithing_trim）→ 1（ProjectE Vanilla/Fallback 覆盖）；其余 0（P2 再对照 EMC 表排重 Integration）。
- snapshot 根加：`recipes[]` + `recipes_total` + `recipe_type_stats{type:{count,covered}}`。
- **已知限制（记录不硬解）**：输入 count 按槽位=1 导出（Ingredient 无 count，Mekanism 等 `{item,count>1}` 配方 count 丢失 → M3 适配器对特定 type 单独补）；空 ingredient（通配）导出为 `"*"`。

### 10.2 P2 改动
- DB 加 `recipes` 表（§3.1 DDL + covered 列 + type 索引）。
- `EMCClient.import_recipes(recipes_list)`：snapshot.recipes → recipes 表（INSERT OR REPLACE）。
- `EMCClient.gap_report(limit=15)`：输出配方缺口报告 = 配方总数/默认覆盖数/未覆盖数 + 未覆盖 recipeType Top N + 涉及 mod 分布。
- `menu.import_and_prep`：import_snapshot 后接 import_recipes + gap_report（顺带打印缺口摘要）。

### 10.3 验收
ATM9（装 ProjectE 1.20.1 + 新版 P1 jar）跑一次 → P2 导入 → gap_report 输出：Create/Mekanism/Thermal/Occultism/BloodMagic/ProductiveBees 的 recipeType 出现在未覆盖清单，且 Integration 覆盖区（若有）不误报。

## 11. 架构调整（2026-08-05 用户反馈）：翻译器内置 mod + 游戏内反馈

- 用户需求：①不想看日志猜进度 → 游戏内**消息栏**显示扫描进度/状态；②P2 能否内置 jar → 希望装 mod 一步到位。
- **决策：M2 起翻译器内置 P1 mod（Java）**——游戏内即有 RecipeManager 全量数据，扫描完成后直接生成 `pe_custom_conversions` datapack JSON（写 config 或世界 datapack 目录），无需退出游戏跑 P2。Python P2 降级为**可选离线分析工具**（保留 recipes 表/gap_report，用于深挖）。
- **P1 已实现（M1.5，已编译部署 dist/EMCAssistant.jar）**：
  - 扫描完成 → `broadcastSystemMessage` 聊天栏广播摘要（物品数/EMC 数/配方数/未覆盖类型数+Top1）；
  - `/emca status` — 随时查扫描状态；`/emca rescan` — 强制重扫（删 snapshot 重跑）；
  - 快照已存在时也广播提示（status/rescan 命令可用）。
- M2 翻译器内置后：扫描 → 翻译 → 生成 datapack JSON → 聊天栏报告"已生成 N 条转换边，写入 X"，玩家 `/projecte reloademc` 即生效。

## 12. M3 产品模式完整设计（2026-08-05 用户设想拍板）

> 用户设想："扫描后将配方的加工方式翻译给等价交换；若其还是不知道怎么赋值，像等价兼容那样给模组**原材料预设 EMC**；之后依赖这些原材料的产物就被 ProjectE 自动算进去。且我们把加工方式也告诉它了。"
> 完整闭环 = **种子值（原料预设 EMC）+ 传播路径（配方转换边）**，缺一不可。

### 12.1 目标形态（对照 Integration 的"下载安装即用"）
- 装 jar → 进游戏 → **零操作**：ProjectE 启动时自动发现我们注册的组件 → EMC 自动算好。
- 两个组件（1.20.1 官方 API，签名已 javap 实证）：
  - **RecipeTypeMapper**（`@RecipeTypeMapper(requiredMods=...)` + 实现 `IRecipeTypeMapper`）→ `handleRecipe` 里 `collector.addConversion(...)` 喂转换边（加工方式）；
  - **原材料预设 EMC** → `collector.setValueBefore(NSS, Long)` 设种子值（原料怎么赋值）。
- `requiredMods` 天然条件激活；我们只覆盖 Integration 未做的 mod（Create/Mekanism/Thermal/Occultism/ProductiveBees 等）→ 与 Integration 天然不重叠不冲突。

### 12.2 原材料预设 EMC 规则（移植 EMC Assistant P2 规则引擎）
- 原料识别（P1 已扫字段）：`forge:ores` / `forge:raw_materials` / `forge:ingots` / `forge:gems` / `forge:dusts` 标签 + **无配方产出**（has_producing_recipe=false）。
- 赋值对标（P2 已验证规则）：
  | 挖掘等级 | 对标 | 基础 EMC |
  |---|---|---|
  | 0 | 石头/木 | 32 |
  | 1 | 石 | 128 |
  | 2 | 铁 | 256 |
  | 3 | 钻石 | 8192 |
  | 4 | 下界合金 | 73728 |
  - 燃料：burn_time 对标；无标签无等级 → 兜底 32。
- 与 Integration 排重：Integration 已预设的原料（其 values.before 覆盖的）不重复设（或 setValueBefore 幂等覆盖，Integration 先设我们后设会覆盖——**需决策：装 Integration 时原料预设跳过**）。

### 12.3 产品 vs 调试分层
- **产品模式（M3）**：内建 RecipeTypeMapper + 原料预设 → 装上即用（正式分发形态）。
- **调试模式（M2 现状）**：`/emca scan/translate/status` 保留 → 缺口诊断、环排查。
- Python P2：可选离线分析（recipes 表/gap_report），不参与正式流程。

### 12.4 M3 实施清单（2026-08-06：全部完成 ✅）
- [x] build.gradle 加 ProjectE 编译期依赖（flatDir libs/projecte-1.20.1.jar，compileOnly）
- [x] 按 mod 的 RecipeTypeMapper ×6（Create/Mekanism/Thermal/Occultism/BloodMagic/ProductiveBees，`requiredMods` 条件激活）+ conditional/mek_data 嵌套跳过（M3.1 处理）
- [x] 原料预设模块（`RawMaterialEmcMapper` @EMCMapper → setValueBefore；演进见 §13）
- [x] 与 Integration 排重（装 Integration 时其覆盖 mod 的原料跳过）
- [x] 编译出"即用 jar"（dist/EMCAssistant.jar）→ ATM9 真实验收（进行中）

### 12.5 产品定位与分工（2026-08-05 用户确认）
- **定位（用户原话校准）**：等价兼容 = 把兼容 mod 的**所有物品预写 EMC** 打包（静态）；我们 = **把加工方式翻译给等价交换（转换边）+ 只预写原材料的 EMC（种子值）**，产物价值由 ProjectE 图算法用"种子 + 加工方式"自己算（动态）。
- **动态方案的优势**：原料 EMC 改动 → 全链自动传导；配方链完整；不需要手工维护每个产物值；规则驱动。
- **⚠️ 5 万条边归因纠正（M2.1 之前截图）**：不是 GT——GT 机器配方不走 RecipeManager，P1 读不到（只能读到 GT datapack 配方如 apiary_ii）；真实构成 = 全包非 vanilla 配方总和（Create/Mekanism/Thermal/PB/Occultism ≈1.1 万 + 无数小 mod 非 vanilla 配方 + GT datapack 部分 + **当时未排重的 Integration 覆盖区重复**）。罪魁祸首 = "全量翻译一切非 vanilla 配方" + 当时缺排重。
- **四家分工（覆盖全包 EMC）**：
  - 我们（M3）：RecipeManager 系（Create/Mekanism/Thermal/Occultism/ProductiveBees）+ 原料预设种子
  - GTToolMapper：GT 机器配方（内部注册表，我们不重复造轮子）
  - ProjectE Integration（等价兼容）：BM/Botania/Ars/AE2/Avaritia 等
  - 原料预设与 Integration 冲突：装 Integration 时其已覆盖原料跳过

## 13. 现行技术路线最终态（M3.1–M3.5 演进，2026-08-05/06）

> 本节为**当前实际运行形态**，与 README 对外描述一致。

### 13.1 组件清单（dist/EMCAssistant.jar，28–34KB，BUILD SUCCESSFUL）
| 组件 | 实现 | 说明 |
|---|---|---|
| `AbstractEmcaRecipeMapper` | 基类 | `canHandle` 前缀分发 + `handleRecipe` 逐条 `addConversion`（通配跳过、tag 多解取第一个、单配方 try-catch 不崩服） |
| `Create/Mekanism/Thermal/Occultism/BloodMagic/ProductiveBeesRecipeMapper` ×6 | `@RecipeTypeMapper(requiredMods=...)` | **mod 不在包内自动不注册**（条件激活）；跳过 mek_data/conditional/catalyst/蜜蜂实体类 |
| `RawMaterialEmcMapper` | `@EMCMapper` | **快速路径**（M3.5）：纯查表注册种子，毫秒级，零遍历，整体 try-catch 隔离 |

### 13.2 种子值三层策略（M3.5 最终）
```
① 逆向表（raw_emc.json，106 条 = 85 item + 21 tag）→ 查表注册 setValueBefore   [参考自 MIT 开源 ProjectE Integration，见 README 声明]
② 16 原版染料 → 手动钉 32（环敏感：互染/分解配方成环被 ProjectE 归零）
③ （M3.2 前有规则引擎/闭环种子：标签原料 + TARGET_MODS 无配方物品遍历——M3.5 因启动性能移除，待 config 开关恢复）
```

### 13.3 关键演进记录
| 迭代 | 内容 | 原因 |
|---|---|---|
| M3.1 | 闭环链种子盲区修复：无配方内部物品预设 | 图论硬约束：入度 0 节点必须预设，否则依赖链永久 0 |
| M3.2 | 逆向 Integration 种子表内置（106 条）+ thermal bug 修复（namespace=thermal 非子 mod id） | 作者校准值比规则引擎准 |
| M3.3 | 16 染料钉 32（优先级高于"有配方跳过"） | 染料互染/分解配方成环 → 环检测归零（原版机制） |
| M3.4 | 遍历快照化（List.copyOf） | CME 防御（后证实主因是重遍历本身，见 M3.5） |
| M3.5 | **零干扰快速路径**：删 collectRecipeOutputs（17 万配方遍历）/estimateValue/5.5 万物品遍历 → 纯查表 ~150 条 setValueBefore + try-catch | 对照实验实锤：启动关键路径重遍历诱发 tombstone NPE / Forge 握手 CME（堆栈无我们 = 间接触发） |

### 13.4 命令集（调试用，产品默认静默）
`/emca status`（模式+摘要）· `/emca scan|rescan`（手动扫描）· `/emca translate`（生成转换边 datapack，M2 遗留）· `/emca missing`（输出无 EMC 物品到 `logs/emc_assistant/missing_report.txt`）· `/emca mode on|off`（开发模式开关，持久化 config/emc_assistant.properties）

### 13.5 已知限制（待办输入）
- mek_data/conditional 嵌套跳过（M3 适配器未解析）；蜜蜂实体类跳过；tag 输入取第一个；输入 count=槽位 1
- 快速路径版无闭环种子/规则引擎（缺口中目标 mod 部分未覆盖，待 config 开关恢复）
- M4 环自检离线预演未做；保底开关（M5）预留未实现

## 14. 赛道侦查：B站"自动设置所有emc值"（2026-08-07）

> 背景：adimn 发现 B站 出现《我的世界自动设置所有emc值》视频，担心赛道被占。K2 下载转写 26 分钟完整内容侦查。

### 14.1 视频信息（BV13M3U6HEhu）
- UP主 bili_6245322（数字号，非专业作者），2026-08-01 发布
- 播放 1.4万 / 赞 477 / **收藏 743**（收藏>投币 = 实用型需求）
- 分发：百度网盘分享"我的模组"（非 CurseForge/Modrinth）
- UP主自述：7 个月前发过一期教程（"讲的非常烂"），这次做了模组成品

### 14.2 其方案与硬限制（转写实证，UP主亲口承认）
- 方案：基于原版 EMC + **合成配方推断**（新物品按原版对标/合成链推断），无推断依据**默认 EMC 8000**（配置文件可改）
- 附带私货：天灯木剑/区块复制器/区块破坏器/纺织机任务/仿制碎片（可关）
- 硬限制①：**无合成配方的物品无法自动赋 EMC**（"必须要有合成，有合成才能推断，要不就得你手动去知道"）——整合包自定义物品（KubeJS/CrT 造的）它无能为力
- 硬限制②：**自动判断物品价值基本不可能**（"他只能这样做了，因为你要是自动的话很难，这基本上不大可能……没有一个判断标准"）
- 硬限制③：半成品（"我现在还在完善""后续再说"至少 5 次）+ 个人网盘分发

### 14.3 对比结论：赛道未被占，需求被验证
| 维度 | B站模组 | 本项目 M3 |
|---|---|---|
| 方案 | 合成配方推断 + 默认 8000 | RecipeTypeMapper 翻译**加工配方**成转换边 + 种子预设，ProjectE 图算法动态算 |
| 覆盖 | 仅"合成"类 | 机器配方（Create/Mekanism/Thermal/Occultism/BloodMagic/PB）——其覆盖不到 |
| 兜底 | 默认 8000 硬编码 | 逆向表 106 条 + 染料钉 32 + 保底开关（M5 预留） |
| 分发 | 网盘个人分享 | dist/EMCAssistant.jar 正规 mod 分发 |
| 成熟度 | 半成品 | M1–M3.5 已交付 |

**决策**：路线不变，继续 §7 阶段 1 稳定性验收（S1/S2/S3）。B站 视频 = "需求已存在"的证明，非"竞品已成熟"的威胁。其方案与本项目 M3 不构成技术重叠（其无机器配方翻译，且无动态种子值机制）。

## 15. 价值传播引擎 v2 设计（2026-08-10，用户拍板定稿）

> **文档状态**：§13（M3.5 快速路径）为现行已交付形态；本节 = 下一个迭代的核心架构（**设计定稿，尚未实现**）。触发背景：S3-2 验收发现 Create crushing 产物无 EMC（配方翻译已提交转换边，但 ProjectE 黑名单把矿石输入清零 → 推导断链），以及 gttoolmapper 污染事件（其 65211 转换 + 10 万零输入种子 + 3442 材料形态钉值 setValueBefore 全覆盖，原版价被毁 → 用户卸载路线）。

### 15.1 动机与决策演进（2026-08-10 讨论实录）

| 议题 | 讨论结论 |
|---|---|
| 预写所有物品 EMC（等价兼容路线） | **放弃**（用户拍板）。全量表难维护、与"矿石不准有 EMC"冲突、gttoolmapper 已证明激进覆盖会毁原版价 |
| 手写逐 mod mapper | **太磨时间**（用户原话），转型为数据/引擎驱动 |
| 直接读 RecipeManager 全部配方 | 已在做（RecipeTypeMapper 就是），但"读到配方 ≠ 有 EMC"：ProjectE 是"种子 + 图推导"，输入无值/成环/流体输出都会断链 |
| **结论** | 自建"价值传播引擎"：种子 = 价值下界，min-迭代传播，环自动取最低，setValueAfter 钉死结果 |

**无 EMC 物品实证全景**（items_snapshot.json 34408 个，2026-08-05）：
```
A. 矿石/原矿黑名单（有意清零）   2,126  (6%)    ← 用户要求保留
B. 有配方但推导断链（可救）    17,832  (52%)   ← 引擎目标
C. 无任何产出配方（救不了）    13,739  (40%)   ← 放弃（GT 装饰 + chipped 装饰品，不参与经济循环）
D. 燃料类                      711    (2%)
```

### 15.2 架构总览（三明治）

```
[种子层]                 [引擎]                      [输出层]
ProjectE 原版表数值 ─┐
EMCA 种子表(raw_emc) ─┼→ min-迭代(类 Bellman-Ford) → setValueAfter 钉死到转化桌
矿石隐含值(1:1) ──────┘   环→最低 · 流体→桶=流体价    矿石/原矿跳过(黑名单保持无 EMC)
```

### 15.3 种子层（三条来源，用户拍板）

1. **ProjectE 原版表数值直接抄入** `raw_emc.json`（defaults + metals 的数值：铁=256、金=2048、8铁=1金 等；数值为事实数据无版权，按我们格式重新组织，不逐字拷贝文件）→ 全 + 可控。
2. **EMCA 现有种子表**：raw_emc.json（106 条）+ 16 染料 + PROCESSED + FLUID（液体）。
3. **矿石隐含值 = 对应锭价 1:1**（1 矿 → 1 锭）：引擎内部有值（供下游推导），**不 setValue**（黑名单保持无 EMC）。

### 15.4 核心算法：min-迭代（环取最低的数学性质）

```
物品价值 = min(种子, 所有产出配方的输入价值和)
```
- **单调下降必有界**（种子为下界）→ 必然收敛。
- **环自动取最低**：环上无种子 → 收敛到无值（同 ProjectE 归零）；环上有任意种子 → 价值沿环传播到环上可达最低值。**16 染料钉值可退役**（引擎接管）。
- **多路径不会刷高**：价值只减不增。副产物与主产物殊途同归 → 只会让下游价值尝试下降，种子锚定不破 → 无交叉对比需求（用户担忧化解）。
- **刷 EMC 入口防护**：真正入口 = "固定值 > 材料成本"，被"唯一来源前置条件"挡住。

### 15.5 规则集（用户拍板）

| 规则 | 实现 |
|---|---|
| 环取最低 | min-迭代数学性质，零特殊代码 |
| tag 多解 | 取 tag 展开后最低值物品（保守下界） |
| 流体输出配方 | 桶物品 = 纯流体价值（容器不计价） |
| **概率副产物** | 前置：该物品**唯一来源** = 概率副产物（无其他产出配方，建图时查产出列表 count==1，O(1)）。公式：`S = max(⌈V_in × p ÷ nS⌉向上取整到72的倍数, 72)`——V_in=输入价值，p=概率，nS=每次数量；72=8×9，保证下游（9粒=1锭、8锭=1金）整除无小数；`max(…,72)` 最低值兜底。例：铁矿石 crushing（隐含 256）经验碎块 p=0.75 → 192 → 取整 216 |
| 矿石黑名单 | 内部有隐含值（推导用），setValue 阶段跳过 |

### 15.6 特例 B/C/D 归宿

| 类 | 引擎解法 | 备注 |
|---|---|---|
| B3 输入清零（矿石系） | 矿石隐含值 1:1 → crushed 等下游自动 = 锭价 | PROCESSED 表退役 |
| B4 嵌套断链 | min-迭代自动贯通整条链 | 无需逐级手写 |
| B5 成环 | 自动取最低 | 染料钉值退役 |
| B6 tag 多解 | 取最低值物品 | 自动 |
| B7 流体输出 | 桶 = 流体价值 | 自动 |
| C 无配方（~1.4 万） | **放弃** | GT/chipped 装饰品不参与经济循环；boss 掉落后续按需定向钉 |
| D 工具/机器 | = 材料总和，材料有值自动有值 | 附魔/NBT 不处理（ProjectE 亦然） |

### 15.7 执行时机（用户担心性能）

- **不塞游戏加载（mapper 阶段）**：8-05 启动崩溃前科（遍历 + 重操作 + 启动关键路径时序敏感）。
- **进世界后异步跑**：建图只读"配方 id + 输入输出 id 字符串"（不构造 ItemStack / 不查 tag / 不查方块等级），17 万配方纯数值迭代毫秒-秒级 → 完成后聊天栏提示"EMC 映射完成，重进世界生效"。
- **分阶段 = config 开关**（`stage.batch=N` 按可达深度分批，每批完成提示重载）——默认关（先全量实测，低配机再开）。

### 15.8 风险与约束（如实记录）

1. **遍历 17 万配方启动安全**：轻量建图规避重操作，但必须真机实测一版确认不拖垮启动。
2. **setValueAfter 无差别覆盖**：引擎取"材料守恒下限"，结果**不会高于** ProjectE 自身推导 → 覆盖只发生在 ProjectE 算不出/算 0 处（正收益），原版价不受影响。
3. **概率副产物定价残余风险**：`V_in×p` 可能略高于副产物实际用途价值（例：经验碎块 216 只能合 1 经验瓶）。用户接受（与"粉碎产物按锭全价不砍半"同一逻辑：接受 mod 量产链整体增值）。转化桌交换等值守恒，不会崩经济。

### 15.9 与现有 M3 的关系

- `RecipeTypeMapper ×6`（§13.1）**保留**：转换边仍提交，是引擎的"配方图"来源之一。
- `RawMaterialEmcMapper` **升级为引擎宿主**：种子层（15.3）+ min-迭代 + setValueAfter 输出。
- PROCESSED / 染料 / 液体钉值：引擎接管后从"手动表"变"引擎产物"（表保留为种子输入）。
- 开发模式命令（§13.4）保留：`/emca` 增加引擎状态/重跑命令（待实现）。

### 15.10 待确认（开工前用户最终点头）

- [ ] 概率副产物公式 `max(⌈V_in×p/nS⌉→72倍数, 72)` 口径确认。
- [ ] 执行时机"进世界后全量异步 + 提示重载"（分阶段开关）确认。
- [ ] 原版表数值抄入范围（defaults + metals 全量 or 仅常用原版物品）。
- [ ] gttoolmapper 卸载动作（原版价恢复）与引擎开发顺序。

## 16. 引擎 v2 生态调研结论（2026-08-10，本地实证 + 联网调研）

> 触发：用户要求开工前先调研——①我们以 jar 身份在 ProjectE 下到底能做什么；②其他领域有没有现成解法（不查论文）。

### 16.1 ProjectE 给我们的能力全景（jar 反编译实证）

| 能力 | API | 状态 |
|---|---|---|
| 配方转换边 | `IRecipeTypeMapper.handleRecipe → collector.addConversion(count, out, inputs)` | 已用（6 个 mapper） |
| 固定值（计算前） | `collector.setValueBefore(nss, v)` | 已用（种子） |
| 固定值（计算后覆盖） | `collector.setValueAfter(nss, v)` | 已用（PROCESSED） |
| 从转换设值 | `collector.setValueFromConversion(count, out, inputs)` | 未用（等价 addConversion 语义变体，可作引擎输出） |
| 算术扩展 | `IExtendedMappingCollector.addConversion(..., IValueArithmetic)` | 未用（NBT/标签算术，高级） |
| 完成信号 | `collector.finishCollection()` | mapper 框架自动调 |
| 液体 NSS | `NSSFluid.createFluid(rl/FluidStack)` | 已用 |
| datapack 表 | `data/<domain>/pe_custom_conversions/*.json`（values.before/conversion + groups） | 已实证（ProjectE 原版/Mek/SecurityCraft 自带） |
| **运行时改值** | `/projecte setemc|removeemc|resetemc` + `/projecte reloademc`（写 custom_emc.json 后重载生效） | **引擎输出通道候选** |
| 运行时查询 | `IEMCProxy.hasValue/getValue/getSellValue` | 已用（RegistryScanner） |
| 缺失导出 | 客户端 `DumpMissingEmc` 命令 | ProjectE 自带，与我们 missing 报告重叠（佐证需求） |
| 许可证 | **ProjectE = MIT**（CurseForge 实证） | 抄数值/参考实现合法（保留版权声明） |

**结论**：ProjectE 给了完整的"种子 + 转换 + 计算前后钉值"能力，且支持运行时改值（reloademc）。我们引擎的"setValueAfter 输出 + 进世界后跑 + 提示重载"通道成立；甚至可编程写 custom_emc.json + 触发 reload（免玩家手动）。

### 16.2 生态调研：MC mod 侧

| 方案 | 机制 | 关键启示 |
|---|---|---|
| **GTToolMapper**（com.flamegazza） | 官方设计 = **"Mapper only"**：只把 GT 机器配方翻译成"材料成本求和"的转换边，**不赋原材料值**（作者明示：原材料需 GTMoreEMC 或自配）。**ModDex 官方最新 = v1.1（7.8KB）**；用户装的 v1.4（13.7KB）带 Skyblock 零输入种子（101583 个）+ 材料形态钉值（3442 个）→ **污染源** | **污染事件最佳解 = 降级到官方 v1.1**（纯配方计算器，原版价恢复 + GT 配方翻译保留），而非卸载。GT 原材料缺口由 GTMoreEMC / 我们引擎补 |
| GTMoreEMC（MeowmelMuku） | 给 GT 矿石/锭/板/电路/工具赋 EMC，按"稀有度/加工复杂度/能耗"动态计算；1.20.1 有 SNAPSHOT | "GT 原材料值"的正规生态方案；注意它给 ores 赋 EMC 可能与 ProjectE 黑名单冲突，需实测 |
| ProjectE-GTCEu-Modern（VincentZhou） | 1.21.1 专用；支持多对一配方；**故意黑名单**（离心机/蒸馏）；**基础流体（水/氧/氢）与废液排除出 EMC 计算**；作者自评"可能有 exploit、Not balanced at all" | **生态对 GT×ProjectE 的共识 = 不完美 + 黑名单 + 流体排除**。我们 1.20.1 用不了它，但它的"流体排除/多对多不支持"验证了我们的规则设计边界 |
| Expanded Equivalence（Zeitheron） | 老 addon（1.19.2/1.12.2 停更）：为特殊合成系统（Draconic Fusion / Avaritia 极致合成）注册 EMC；**Botania 花因 NBT 识别无 EMC**；**Avaritia 物品超 Java int 上限（21 亿）被排除** | 验证：①特殊机器配方注册 EMC 是成熟方向 ②**NBT 物品放弃是生态共识**（我们 D 类 ✓）③**超 int 值要防溢出**（我们用 long，但 ProjectE 内部值域需核对） |
| ProjectE addon 家族全景（namu.wiki） | Equivalent Integration / Project EX / Expanded Equivalence / Equivalent Energistics / Refined Exchange / GTMoreEMC / GTToolMapper / ProjectE-GTCEu-Modern | **全生态无一家做"通用全配方价值引擎"**——全是"预写表 + 特定配方翻译"。我们的引擎 v2 空位成立 |

### 16.3 生态调研：其他领域（生产链计算器，非论文）

| 工具 | 解法 | 启示 |
|---|---|---|
| Production Chains Calculator（EldritchTools，Factorio/Satisfactory/DSP/Anno） | Compute Method：**Simple / Matrix / LP-Force / LP-Optimize**。Matrix 解"多配方 + 环"；LP 最小化副产品 | **环 = 全行业公认难点**（该工具 Matrix 才能解；tbjoern 的 ProductionChainCalculator 作者把"循环依赖"列在 wishlist 未实现）。**我们 min-迭代 = Simple 档**：对"价值标定"（下界语义）够用且匹配 ProjectE 哲学；Matrix/LP 是"生产比例"解法，超纲且引库重（工作量爆炸，用户已拒绝） |
| FactorioLab（Satisfactory） | 矩阵求解器处理复杂回收环 | 同上 |
| 结论 | 配方图价值计算 = 图论问题，业界解法从"迭代"到"线性规划"谱系；我们取最简档即可 | **min-迭代决策维持**；若未来要精确解环再评估 LP（不推荐） |

### 16.4 对引擎 v2 的修正与确认（本调研产出）

1. **GTToolMapper 污染事件修正**：不卸载、**降级 v1.1**（官方渠道）→ GT 配方翻译保留 + 原版价恢复；GT 原材料值后续由 GTMoreEMC 或我们引擎的种子层补。**已写入 §7 待办**。
2. **引擎输出通道确认**：运行时 `/projecte setemc` + `reloademc` 存在 → 引擎可编程写 custom_emc.json + 触发重载（备选；首版仍用"setValueAfter 进世界后跑 + 提示玩家重载"更稳）。
3. **值域防护**：EMC 用 long；对超 int 物品（Avaritia 类）注意 ProjectE 内部计算上限，min-迭代加溢出保护（clamp）。
4. **NBT 物品**：确认放弃（生态共识，Expanded Equivalence 同样放弃 Botania 花）。
5. **流体排除**：参考 ProjectE-GTCEu-Modern 做法，基础流体（水/氧/氢/空气等）与废液在引擎内排除（不参与推导，避免污染链条）——§15.5 规则补充。
6. **空位成立**：无竞品做通用引擎，我们的差异化 = "RecipeManager 全量配方 + min-迭代 + 种子层 + 规则集"的组合。

### 16.5 rs_integration 参考（2026-08-14 补充，gh 源码实证）

> 来源：用户发现 rs_integration（Elten-huanghuang，Refined Storage 递归自动合成，v1.3.2，1.20.1 Forge）——做"嵌套合成执行"，与 EMC 引擎**同构不同层**（它执行合成、我们标定价值）。**专有许可（Proprietary License, All rights reserved, NOASSERTION）→ 只借鉴思想，零代码复制。**

对 EMC 引擎 v2 的**可借鉴点**（按价值排序）：

1. **RecipeIndex 建图结构**（`Map<Item, List<Entry>>` 统一索引 + `ModRecipeHandler` 按 mod 提取配方）——与我们引擎 v1 建图（RecipeManager 全量 + RecipeTypeMapper 按 mod）同款，其"单次构建 + 版本化复用"更清晰。**引擎 v1.1 建图重构参考**。
2. **搜索护栏体系**（maxSearchStates=65536 / callDepth=512 / deadlineNanos=500ms / 失败记忆去重=8192）——我们 min-迭代目前只有 MAX_ITERATIONS；**补 deadlineNanos 时间预算 + 失败记忆**，防异常图（深链/多配方）拖垮（§15.8 风险①的加固）。
3. **版本化缓存**（`sourceRevision` + recipeManager 引用比对，构建一次复用）——我们引擎每次启动全量建图（17 万配方秒级），可借鉴"同一 recipeManager 引用 + 世界切换失效"的增量缓存，**二次进世界省一次建图**。
4. **no-gain 剪枝**（等价材料族互转剪枝：羊毛染色/木头家族）——min-迭代"环取最低"已覆盖环值问题；若实测某类环导致迭代轮数多/值怪异，可借鉴其"等价互转配方剪枝"（保留等量/增量配方，剪掉纯等价互转）作为**迭代前预处理**。
5. **纯值不可变图**（record，线程安全）——我们引擎建图可同样用不可变结构，支持异步规划（引擎跑在 Worker 线程，未来若移到进世界后异步，不可变图免锁）。

**不借鉴**：RS 网络交付、机器调度、事务分配、GUI 渲染层（那些是执行/生态层，与 EMC 标定无关）。

**状态**：以上 1/2/3 已纳入引擎 v1.1 实施候选（§15.10 待确认外的新增项）；4 为条件项（实测触发才做）。

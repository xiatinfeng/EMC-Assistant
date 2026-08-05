# 配方集成调研：让 ProjectE 认识科技 & 魔法配方

> 2026-08-05 · EMC Assistant 前置调研
> 背景：EMC Assistant 只标"原材料"；有配方的物品交给 ProjectE 自动算。但 ProjectE 默认只认 vanilla crafting/smelting，机器配方（GT/Create/Mekanism…）和仪式配方（Goety 诡厄巫法…）都不认识 → 产物无 EMC。本调研回答：**这些配方能不能被读到、能不能翻译成 ProjectE 认识的"转换边"**。

## 0. 结论先行

1. **能。** 现代 mod 的加工配方（科技+魔法）绝大多数是**标准 datapack recipe**（`data/<mod>/recipes/*.json`，注册进 RecipeManager），可以被通用读取。
2. **魔法配方没有本质障碍。** 最具代表性的 Goety 仪式合成同样是 datapack recipe（`type: goety:ritual`），有完整的 `ingredients → result` 结构，甚至有 KubeJS-Goety 官方集成证明其配方系统完全开放。
3. **翻译动作可以完全离线（P2 工具内）**，不必写 Java mod：扫 mods jar 里的 recipe JSON → 生成 ProjectE 的 `customConversions` JSON → 写进 config。**喂给 ProjectE 的是"转换边"（动态算 EMC），不是写死的产物值**——这正是区别于"等价兼容预写 EMC"的关键。
4. 用户已拍板：**不加加工系数**（产物 EMC = Σ材料 EMC，1:1 守恒）；灵魂能量/魔力/电量等非物品成本**接受被忽略**（图算法本质只认物品输入输出）。

## 1. ProjectE 侧：它认识什么、怎么喂

- EMC 内核 = 图算法（`SimpleGraphMapper`）：固定值 + 所有转换边（crafting/smelting/custom conversions）→ 有向图 → 迭代收敛 → 环检测防刷（有环设 0）。
- **原生扩展口：`config/ProjectE/customConversions/*.json`**（`CustomConversionMapper` 读），每条 `conversion = {count, output, ingredients}` 即一条转换边；物品标识支持 `OD|`（矿物词典）/`FAKE|`（虚拟中间节点）/`FLUID|`（流体，1.12 版验证）；另有 `values.setValueBefore/After/conversion` 定固定值/优先级边。
- 收集层 API：`addConversion` / `setValueBefore` / `setValueAfter` / `setValueFromConversion`（若走 mod 内注册路线）。
- ⚠️ 诚实标注：customConversions 格式取自 ProjectE master（1.12.2 时代）源码；**1.20.1 版格式以游戏内生成的 `example.json` 为准**（首次启动自动生成，本身就是模板）。tag（物品标签）在 1.20 版 customConversions 里如何表达待验证（1.12 用 OD|，1.20 无 OreDictionary，可能需展开成物品列表或用 ProjectE 内部 tag 支持）。

## 2. 配方侧：科技 & 魔法配方都能读到

### 2.1 科技配方（机器加工）
- **GT CEu Modern**：机器配方在 GT 内部注册表（非标准 RecipeManager）→ 已有现成先例 **GTToolMapper**（CurseForge 1439351，Forge 1.20.1，2026-01）：扫描 GT 配方注册表喂 ProjectE，递归补链+防环，只算配方不定原材料（与 EMC Assistant 互补）。→ **GT 直接蹭，不用写**。
- **Create / Mekanism / Thermal 等**：配方均为 datapack JSON + 自定义 RecipeType，注册进 RecipeManager → 可枚举可解析（需为每个 RecipeType 写解析规则，但数据源统一）。

### 2.2 魔法配方（仪式合成，以 Goety 为例）
- **配方文件**：`data/goety/recipes/*.json`，`"type": "goety:ritual"`。实例（万灵药，mcmod.cn 已核实）：
```json
{
  "type": "goety:ritual",
  "ritual_type": "goety:craft",
  "craftType": "necroturgy",
  "activation_item": { "item": "minecraft:glass_bottle" },
  "soulCost": 1000,
  "duration": 60,
  "entity_to_sacrifice": { "tag": "goety:villagers" },
  "ingredients": [ { "item": "goety:unholy_blood" }, "...共12项..." ],
  "result": { "item": "goety:undeath_potion" }
}
```
- **翻译规则（按用户拍板）**：只取 `ingredients → result` 为转换边；`soulCost`/`duration`/`entity_to_sacrifice`/`activation_item`/`research`/`craftType`（结构要求）**全部忽略**。soulCost 是 Goety mod 内货币（灵魂能量），图算法无此概念，接受被忽略。
- 开放性佐证：**KubeJS-Goety**（AmicBeam）官方集成，`ServerEvents.recipes` 可读写 ritual/brewing/pulverize/cursed_infuser 配方 → 证明 Goety 配方系统是标准开放体系。
- **其他类似仪式 mod**（同属 datapack recipe 的候选）：血魔法（Blood Magic，ritual 部分为 mod 内部祭坛配置，需单独查）、Occultism（ritual 走 datapack，需单独查）、Mana and Artifice 等——**按目标整合包 mod 列表逐个核**，规则一致：找 `data/<mod>/recipes/*.json` 里的自定义 recipeType。

## 3. 三条实现路线

| 路线 | 做法 | 优点 | 代价 | 适配 |
|------|------|------|------|------|
| **R1 工具离线翻译（推荐）** | P2 扫 mods jar 的 recipe JSON → 生成 customConversions JSON 写 config | 零 Java、与 EMC Assistant 现有架构完全一致（工具读→写 config）、动态算 | 拿不到运行时才注册的配方；tag 展开要离线解析 `data/<mod>/tags/` | 科技+魔法全覆盖，按 recipeType 写解析器 |
| **R2 mod 内翻译** | P1 扩展：启动时扫 RecipeManager → 走 ProjectE API/addConversion 或生成 JSON+reloademc | 拿到运行时配方、tag 可展开成物品、最"正统" | 要写 Java、依赖 ProjectE 公开 API（入口待验证） | 同上 |
| **R3 蹭现成** | GT → GTToolMapper；其他 mod 找现成集成 mod | 零成本 | 覆盖面随社区走，不等我们的节奏 | 仅 GT 等有现成的 |

**推荐：R1 打底（工具翻译器）+ R3 补 GT。** R2 留作 R1 搞不定的 mod 的后备（如配方运行时才生成、或 tag 展开离线做不到时）。

## 4. 待验证项（Phase 0 清单）

1. **ProjectE 1.20.1 customConversions 实际格式**：启动游戏看 `config/ProjectE/customConversions/example.json`；确认 tag 表达方式。
2. **`/projecte reloademc` 能否热加载新生成的转换文件**（能则工具改配置→游戏内重载即可，无需重启）。
3. **目标整合包 mod 清单**：逐 mod 核配方落点（datapack recipe / 内部注册表 / 特殊系统），统计 recipeType 种类。
4. 非 datapack 配方 mod（如 GT）的兜底方案确认。

## 5. 与 EMC Assistant 现有架构的衔接

- P1（游戏内扫描）现有能力可扩展：把"识别出的 recipeType 分类报告"加进 snapshot → 直接产出 **配方缺口报告**（哪些配方类型 ProjectE 未识别、涉及多少物品）。
- P2（工具）新增模块：**RecipeTranslator**——输入 mods jar 列表 → 输出 customConversions JSON。
- 设计原则：喂转换边（动态），不写死产物 EMC；"物质之间万物等价"（全物品 EMC 互通）为下一大目标，本期只打通"让等价读加工逻辑"。

## 6. 两步走落地（用户既定）

1. **本期**：先出配方缺口报告 + R1 翻译器原型（先覆盖目标整合包的科技配方，再补 Goety 等魔法配方）。
2. **下期大目标**：万物等价（所有生产途径都成为转换边 + 所有物品有 EMC）。

## 7. 第二轮设计更新（2026-08-05）：多配方策略与 0 传染防火墙

### 7.1 多配方取 min（用户悖论分析）
- ProjectE 图算法（SimpleGraphMapper）多配方默认**取最小**（min value wins，防刷设计，内核写死，翻译器改不了）。
- 用户悖论确认：min → 玩家用贵配方合成就亏（产物只能换回最便宜成本）；max → 玩家用便宜配方合成再炼金套利（刷 EMC）。min 是 ProjectE 已选的保守侧，接受之。
- **可动的是覆盖层**：`customConversions` 的 `values.setValueAfter` 可对指定物品钉死 EMC（图算法算完后覆盖）→ 用于修复 0 传染/环受害品，而非全量预写。

### 7.2 "多配方设 0"拆解（待 Phase 0 实测验证，不臆断）
- 现象两种成因，需用真实游戏构造小样例区分：
  - (a) **环检测**（合理）：配方链交叉引用成环（A 配方要 B，B 配方要 A）→ 环内物品 EMC 设 0，防刷机制，正确行为。
  - (b) **0 传染**（要防）：某中间产物 EMC 为 0 → 依赖它的所有下游产物也变 0（图算法自底向上，材料 0 → 产物 0，除非有另一条无 0 路径且 min 策略实际行为待实测）。
- ⚠️ 关键不确定点：min 策略下，一条边含 0 EMC 材料时，ProjectE 是"该边算 0"还是"该边无效、用其他边"？**决定 0 传染是否必然发生**。Phase 0 验证项：构造 {B=0, A=B+C} 与 {A=B+C, A=D+E 两条边} 小样例实测。
- 用户观察"多配方多模组 → 设 0"很可能是 (a) 环：多模组配方交叉引用最容易成环（mod A 产物是 mod B 材料、mod B 产物是 mod A 材料）。翻译器需**环自检**（离线图检测，先于 ProjectE 发现）。

### 7.3 架构升级：0 传染防火墙（翻译器新模块）
```
RecipeTranslator 产出流程：
1. 扫 jar recipe JSON → 生成转换边（ingredients→result）
2. 离线复刻 min 图算法（或读 ProjectE DumpToFileCollector 输出）→ 预演 EMC 结果
3. 依赖影响分析：找 EMC=0 的产物 → 标 0 传染源 → 沿依赖链列出受害物品
4. 生成覆盖建议：对关键中间产物生成 setValueAfter 钉值（= Σ材料 EMC 手动算）
   └ 钉值切断 0 传染，避免下游全 0；覆盖表 = 白名单式最小干预，不做全量预写
5. 环自检通过 + 覆盖表就位 → 写 config
```
- 设计原则不变：**转换边动态算为主，setValueAfter 仅当防火墙**（用户否定的"全量预写 EMC"不做，但定点修复保留）。

### 7.4 测试整合包拍板：**ATM10（All the Mods 10）**
- 1.20.1 Forge 巨型包（400+ mods），与用户环境匹配。
- 科技侧预期覆盖：Create / Mekanism / Thermal 系列 / Resourceful Bees（资源蜜蜂）等；**GT 不在 ATM10**（GTToolMapper 用不上，需自带 Create/Mekanism/Thermal 翻译）。
- 魔法侧预期：需核 ATM10 是否含 Goety / Occultism / Blood Magic / Ars Nouveau 等，定魔法翻译器首期范围。
- 下一步动作：拉 ATM10 modlist → 统计 recipeType 种类 → 出配方缺口报告。


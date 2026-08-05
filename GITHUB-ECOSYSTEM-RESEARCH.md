# ProjectE 生态 & 底层修改调研（GitHub 大范围 + 保底机制可行性）

> 2026-08-05 · EMC Assistant 前置调研第二部分
> 两个调研题：①GitHub 上有没有等价交换附属/集成做出来了（哪怕一点，作技术路径参考）；②能否注入修改 ProjectE 底层逻辑（以"1 EMC 保底机制"为例）。

## 0. 结论先行

1. **生态有货，且 1.20.1 现役有一个直接相关的开源 mod**（ProjectE-EMC-Expansion，MIT）——它已做"无 EMC 物品自动算值"，但以 vanilla/普通配方为主；**"机器+仪式配方 + 0 传染防火墙 + 保底"仍是空白**，我们的差异化成立。
2. **ProjectE 1.20.1 有公开 API `moze_intel.projecte.api.EMCApi`**：`getEMCValue(ItemStack)` / `setValue(ItemStack, long)` / `hasEMCValue(ItemStack)`（现役 mod 用反射调用证实）→ **运行时直接写 EMC，不必生成 JSON + reload**。
3. **改底层可行且有先例**：Expanded Equivalence（1.12.2，百万下载）作者原话"ProjectE custom EMC mappers are hardcoded，I used ASM to fix that"。1.20.1 Forge 标准做法是 Mixin。
4. **保底机制实锤了一个成因，但暴露一个风险**：玻璃板"设 0"不是 ProjectE 故意设 0，是 **`HiddenBigFractionArithmetic` 向下取整**（6/16=0.375→0，防刷设计）。保底到 1 后该配方变成**放大边**（6 玻璃=6 → 16 板=16），**可自动化刷 EMC**（玻璃机+自动合成+冷凝器挂机）。保底必须配白名单或 Mixin 联动防放大。

## 1. GitHub / 生态盘点（诚实标注）

| 仓库/Mod | 版本 | 开源 | 干了什么 | 对我们的价值 |
|---|---|---|---|---|
| **ProjectE-EMC-Expansion**（oreoorin6） | 1.12.2–1.21.1（含 1.20.1 ✅） | ✅ MIT | 无 EMC 物品自动算值：配方扫描 + 材料/稀有度回退 + `emcMultiplier` 全局乘数配置 | **技术路径参考 No.1**：多版本 source set 组织、反射调 ProjectE API 的模式 |
| **Expanded Equivalence** | 1.12.2（百万下载） | ❌ 闭源（作者帖文公开细节） | DE Fusion Crafting / Avaritia extreme crafting 等特殊合成系统 → EMC | **注入改底层先例**："mappers are hardcoded, I used ASM to fix that" |
| **Equivalent Integrations**（pkmnfrk） | 1.12.2 | ✅ | 机器自动化访问 EMC/转化：IEMCManager capability 抽象层 | ProjectE API 的坑记录（离线玩家不可变、无 EMC 变化事件）→ 边界认知 |
| **AppliedE**（62832） | 1.20.1 | ✅ LGPL | EMC 作为 AE2 ME 网络 key type（早期 beta） | "改 EMC 语义"的远例 |
| **GTToolMapper**（Flamegazza321） | 1.20.1 | ❌ | GT 机器配方（Assembler/Chemical Reactor/Fluid Extractor）→ EMC，递归补链+防环 | 机器配方映射已被验证可行（同平台同版本） |
| EMC Assistant（我们） | 1.20.1 | — | 标原材料 + （规划中）机器/仪式配方翻译 + 0 传染防火墙 + 保底 | **差异化：三者组合无人做** |

生态结论：**做"认识机器/仪式配方"不是没人想过**（Expanded Equivalence 做了 1.12.2 的特殊合成），但 **1.20.1 现役 + 通用配方翻译 + 经济安全（0 传染/防放大/保底）的组合是空位**。技术抄作业清单：EMC-Expansion 的反射 API 兼容层 + Expanded Equivalence 的注入思路 + GTToolMapper 的机器配方扫描思路。

## 2. ProjectE 1.20.1 公开 API（已从 EMC-Expansion 源码实证）

```java
Class.forName("moze_intel.projecte.api.EMCApi");
emcApiClass.getMethod("getEMCValue", ItemStack.class);          // 查 EMC
emcApiClass.getMethod("setValue", ItemStack.class, long.class); // 设 EMC（运行时直接写！）
emcApiClass.getMethod("hasEMCValue", ItemStack.class);          // 有没有 EMC
```
- 作者标注 `direct_api=false, reflection_api=true` → **反射调用是现役 mod 验证过的稳路**。
- ⚠️ 待验证：`setValue` 的语义——是图算法前注册固定值（≈setValueBefore）还是算法后覆盖（≈setValueAfter）？**决定它能否当"保底"用**（Phase 0 读 ProjectE 1.20.1 源码确认）。
- 局限（Equivalent Integrations 记录）：ProjectE 不支持离线玩家 EMC 突变、无 EMC 变化事件 → 涉及玩家 EMC 的自动化要自己包抽象层（我们暂不碰，仅记录）。

## 3. 注入修改底层：可行，但要先问"要不要"

- **先例**：Expanded Equivalence 用 ASM 改 ProjectE 的 mapper 注册（1.12.2）→ 证明"注入改底层"有人走通，且那是"不加 mapper 就做不了特殊合成 EMC"的刚需场景。
- **1.20.1 路径**：Forge Mixin（标准）注入 `moze_intel.projecte.emc.SimpleGraphMapper` 等内部类。ProjectE 开源（sinkillerj/ProjectE；1.20.1 维护分支需确认）→ 可读源码找 hook 点。
- **但先问"要不要"**：保底/0 传染优先用**配置层 + API 层**解决（不碰底层，跟着 ProjectE 升级不用改），Mixin 只在"配置层表达不了"时上（如全局 clamp ≥1 且要过防放大）。原则：**能用配置解决的不用 API，能用 API 解决的不用 Mixin**。

## 4. 保底机制分析（用户玻璃板例子）

### 4.1 成因实锤：取整归 0，不是"故意设 0"
- `HiddenBigFractionArithmetic`：BigFraction 内部计算，**结果向下取整**（rounds down，防 EMC 复制刷取）。
- 6 玻璃（6 EMC）→ 16 玻璃板：每板 = 6/16 = 0.375 → **取整 = 0** → 玻璃板不可转化。用户观察正确，且机制是"取整截断"。

### 4.2 保底需求成立，但保底 = 放大边风险
- 需求：EMC < 1 的物品在等价交换里不可转化，保底到 1 让它们可用。用户经济判断（绝对量小、没人刷）对"单物品"成立。
- ⚠️ **风险**：保底后 6 玻璃(6) → 16 板(16)，净赚 10 EMC/循环，且**可全自动**（沙子无限 → 玻璃机 → 自动合成 → 冷凝器挂机）。整合包 N 入 M 出(M>N) 配方遍地，**保底 = 系统性放大后门**。ProjectE 防放大（测试例：Coal→2 Alchemical Coal 产物设 0）用精确值判定，保底强制覆盖（setValueAfter 语义）防不住。

### 4.3 实现三选
| 方案 | 做法 | 优点 | 代价 |
|---|---|---|---|
| **a. Mixin clamp** | 注入 SimpleGraphMapper 输出，EMC 全局 clamp ≥1 | 最干净、全覆盖 | 要读源码+跟版本；必须理清 clamp 与防放大判定的先后 |
| **b. setValueAfter 覆盖表** | 工具算出 <1 物品 → 钉 1（不碰底层） | 快、与工具架构一致 | 放大边防不住 → 必须配**白名单**（只保底无可自动化放大链的物品） |
| **c. b + 白名单** | 覆盖表 + 白名单限定保底范围 | 推荐：安全、轻 | 白名单维护成本；漏网放大边需定期审计 |

**推荐 c**：保底用"覆盖表 + 白名单"，白名单规则 = 排除"存在 N 入 M 出(M>N) 自动可重复配方链"的物品；Mixin 留作远期全局方案。

## 5. Phase 0 验证清单（更新）

1. **拉 ProjectE 1.20.1 源码**（确认 sinytra/KevyPorter 维护分支）：读 `EMCApi.setValue` 语义（图算法前/后）→ 决定它能否当保底写入；读 `SimpleGraphMapper` 防放大确切规则（Coal→2 Alchemical Coal 触发边界）。
2. **游戏实测**：玻璃板实际 EMC（验证取整归 0）；构造 6 玻璃→16 板小样例，验证保底后 ProjectE 行为（放大边是否被拦）。
3. **customConversions 1.20.1 格式**：看游戏生成的 example.json。
4. **/projecte reloademc**：热加载验证。
5. **ATM10 modlist**：recipeType 统计（技术缺口报告的地基）。
6. 保底白名单规则原型：离线预演全物品 EMC → 标出 <1 物品 → 检查各自配方链是否可自动放大 → 生成白名单建议。

## 6. 待决策

- 保底方案选 **c（覆盖表+白名单）** 还是 **a（Mixin clamp）**？（建议 c 起步，a 远期）
- 是否先 clone ProjectE 1.20.1 源码做 §5.1 验证（需要网络 + 时间，产出 API 语义确认报告）。

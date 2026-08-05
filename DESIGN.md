# EMC Assistant — 设计文档 v0.2

> 2026-07-08 更新

> **⚠️ 历史基线（2026-08-06 标注）**：本文档为 EMC Assistant 的**原始设计**（2026-07），描述的是"P1 扫描 mod + P2 本地标注工具（规则引擎 + LLM）"架构。
> **已过时**——项目主线已演进为 **M3 产品模式 mod**（配方翻译 RecipeTypeMapper + 原料种子预设，装 jar 即用），现行设计见 [`RECIPE-TRANSLATOR-DESIGN.md`](RECIPE-TRANSLATOR-DESIGN.md) §12/§13。
> **保留价值**：①"五道判断逻辑"（标签 / 挖掘等级 / 有配方跳过 / 工具属性 / LLM）= 当前"原料预设种子值"的**思想来源**；②P2 工具已降级为可选离线分析（原料标注仍可用本文档逻辑）。

## 项目主线

> **找出整合包里"该标 EMC 但还没标"的原材料，让玩家自己决定标多少。**

三层分级：

| 层级 | 负责 | 解决的问题 |
|------|------|-----------|
| P1 运行时探测 Mod | 游戏内扫描物品 + ProjectE 反射 | 找出缺 EMC 的物品清单 |
| P2 本地 PC 工具 | 规则引擎 + LLM 辅助标注 | 给原材料定合理 EMC 值 |
| 配方链推导(待实现) | 配方树→基础原材料 | 跳过有配方的物品，只标根节点 |

## 核心判断逻辑

### 第一道：标签过滤（最可靠）

Forge 标签天然区分原材料和加工品：

```
forge:ores/*             → ✅ 原材料（矿物）
forge:raw_materials/*    → ✅ 原材料
forge:ingots/*           → ✅ 无配方 → 原材料 / 有配方 → ProjectE 自动算
forge:gems/*             → ✅ 同 ingots
forge:dusts/*            → ✅ 同 ingots
forge:tools/*            → ⏭️ 跳过（ProjectE 按配方自动算）
forge:armor/*            → ⏭️ 跳过
forge:foods/*            → 🤖 LLM 判断
```

### 第二道：挖掘等级对标

P1 已扫的 `harvest_level` 直接对应原版 EMC：

| 挖掘等级 | 对标原版 | 基础 EMC |
|---------|---------|---------|
| 0 | 无工具要求（石头/木） | 32 |
| 1 | 石级 | 128 |
| 2 | 铁级 | 256 |
| 3 | 钻石级 | 8192 |
| 4 | 下界合金级 | 73728 |

### 第三道：有配方产出的跳过

`has_producing_recipe = true` → 让 ProjectE 根据配方自动计算 EMC。
玩家不需要手动标这些物品。

### 第四道：工具属性倍率对标（待实现）

如果该原材料有对应的工具（如钴矿→钴镐），对比工具属性与同级原版工具：

```
钴镐 vs 下界合金镐：
  挖掘速度 +33%
  耐久    +23%
  攻击力  +17%
  综合    ≈ ×1.3
→ 钴锭 EMC ≈ 73728 × 1.3 ≈ 95846
```

护甲同理：
```
钴胸甲 vs 下界合金胸甲：
  护甲值 +2
  韧性   +1
→ 钴锭 EMC 上调级距
```

**P1 需要增强**：当前只扫了 `harvest_level`，需要增加：
- 工具：挖掘速度、攻击力、耐久
- 护甲：护甲值、韧性
- 武器：攻击速度、攻击伤害

### 第五道：无标签无配方无等级 → LLM 二分类

LLM 只回答一个问题："这是原材料还是加工品？"
- 原材料 → 找最近的参照物 → 查表赋值
- 加工品 → 跳过（可能是模组机器产物，无配方无法自动推导）
- 两者都不是 → 低置信度标记，保留人工确认

## 两轮迭代方案

```
[第一轮]
  1. 导入 snapshot
  2. 遍历所有物品
     ├─ 已有 EMC → 跳过
     ├─ 有配方产出 → 跳过（ProjectE 自动算）
     ├─ 高置信度原材料 → 规则引擎标 EMC
     └─ 不确定 → LLM 分类 + 参照赋值
  3. 导出配置 → 启动游戏 → ProjectE 自动算通配方链

[第二轮]
  1. P1 重扫（现在配料都有 EMC，之前算不出的现在能算了）
  2. 还缺 EMC 的 → LLM 兜底（模组机器产物/特殊获取渠道）
```

## EMI 配方树算法参考

EMI 递归展开到基础原材料的逻辑可直接用于 EMC 链式推导：

```
calculateCost(node, amount):
  if node.recipe exists:
    for each child (ingredient):
      calculateCost(child, minBatches × child.amount)
  else:
    // 无配方 → 基础原材料
    addCost(node.ingredient, amount)
```

参见 EMI mod 源码的 Bom 树实现（xplat/src/main/java/dev/emi/emi/bom/ 下）：
- `MaterialTree.java` — 树结构 + 递归展开
- `MaterialNode.java` — 节点：配方/子节点/数量
- `TreeCost.java` — 成本计算：展开到无配方的叶子节点后求和

### 应用到 P2 的思路

P1 已扫 `has_producing_recipe`。P2 虽然没全量配方数据，但可以：

1. 有配方产出 → 跳过（交给 ProjectE）
2. 无配方 + 标签为原材料 → 标 EMC
3. 无配方 + 不确定 → LLM 判断
4. （未来）P1 增强：导出配方配料关系 → P2 做完整的链式推导

## 数据栈（当前）

```
_emc_cache/            ← exe 同目录便携缓存
  ├─ emc_assistant.db  ← SQLite 主库
  └─ config.json       ← LLM API 配置

items 表：
  item_id, mod_id, emc_value, source,
  is_fuel, burn_time, harvest_level,
  created_at, updated_at
```

### 待加字段

当前只存了 4/14 个 P1 扫描字段。需要补充：

```sql
ALTER TABLE items ADD COLUMN has_producing_recipe INTEGER;
ALTER TABLE items ADD COLUMN is_raw_material INTEGER;
ALTER TABLE items ADD COLUMN is_raw_material_candidate INTEGER;
ALTER TABLE items ADD COLUMN tags TEXT;  -- JSON array
ALTER TABLE items ADD COLUMN classifications TEXT;  -- JSON
```

### 新增：item_properties 表

用于存放工具/护甲属性（P1 增强后）：

```sql
CREATE TABLE item_properties (
  item_id TEXT PRIMARY KEY,
  item_type TEXT,         -- 'tool'/'armor'/'weapon'
  harvest_level INTEGER,
  mining_speed REAL,
  attack_damage REAL,
  attack_speed REAL,
  durability INTEGER,
  armor_value INTEGER,
  armor_toughness REAL,
  enchantability INTEGER
);
```

## 架构（当前）

```
EMC-Assistant.exe
├─ 首次运行 → 选 LLM 提供商 + 填 Key → 生成 config.json
├─ 选整合包版本文件夹
│  ├─ 检测 snapshot 不存在 → 自动部署 P1 jar
│  └─ 检测 snapshot 存在 → 自动导入
├─ 两个选项:
│  [1] 本地规则引擎（标签+挖掘等级 → 秒级完成）
│  [2] 大模型精调（规则引擎 + LLM 参照 → ~2分钟）
└─ 自动写回游戏 config/ 目录
```

## 项目节点（更新）

| 节点 | 状态 |
|------|------|
| P0 调研 | ✅ 完成 |
| P1 Mod（游戏扫描） | ✅ 完成（待增强工具属性）|
| P2 本地工具 | ✅ 完成 |
| P2.5 导入全字段 | ⬜ 待做 |
| P2.6 EMI 配方链算法 | 📝 已记录 |
| P2.7 工具属性对标 | 📝 已记录 |
| P3 exe 打包 | ✅ 完成 |
| 柠娜风格菜单 | ✅ 完成 |
| 便携缓存目录 | ✅ 完成 |
| 首次运行向导 | ✅ 完成 |

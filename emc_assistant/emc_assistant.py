#!/usr/bin/env python3
"""EMC Assistant - Local PC Tool (P2)."""

import argparse, os, sys, json, sqlite3
from datetime import datetime

APP_NAME = "EMC-Assistant"
if getattr(sys, 'frozen', False):
    _BASE = os.path.dirname(sys.executable)
else:
    _BASE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DB_DIR = os.path.join(_BASE, "_emc_cache")

DB_SCHEMA = """
CREATE TABLE IF NOT EXISTS mods (
    mod_id TEXT PRIMARY KEY, mod_name TEXT, version TEXT,
    pei_supported INTEGER DEFAULT 0,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS items (
    item_id TEXT PRIMARY KEY, mod_id TEXT NOT NULL REFERENCES mods(mod_id),
    display_name TEXT, emc_value INTEGER, source TEXT DEFAULT NULL,
    is_fuel INTEGER DEFAULT 0, burn_time INTEGER DEFAULT 0,
    harvest_level INTEGER DEFAULT 0, confidence INTEGER DEFAULT 0,
    has_producing_recipe INTEGER DEFAULT 0,
    is_high_confidence_raw_material INTEGER DEFAULT 0,
    is_raw_material_candidate INTEGER DEFAULT 0,
    classifications TEXT, tags TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS snapshots (
    snapshot_id TEXT PRIMARY KEY, created_at TEXT DEFAULT (datetime('now')),
    mod_count INTEGER DEFAULT 0, item_count INTEGER DEFAULT 0,
    items_with_emc INTEGER DEFAULT 0, items_without_emc INTEGER DEFAULT 0,
    total_mods INTEGER DEFAULT 0, projecte_available INTEGER DEFAULT 0,
    game_version TEXT, mod_version TEXT
);
CREATE TABLE IF NOT EXISTS snapshot_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id TEXT NOT NULL REFERENCES snapshots(snapshot_id),
    item_id TEXT NOT NULL, has_emc INTEGER DEFAULT 0,
    emc_value INTEGER DEFAULT 0, has_pei_override INTEGER DEFAULT 0,
    has_producing_recipe INTEGER DEFAULT 0, is_fuel INTEGER DEFAULT 0,
    burn_time INTEGER DEFAULT 0,
    is_high_confidence_raw_material INTEGER DEFAULT 0,
    is_raw_material_candidate INTEGER DEFAULT 0,
    harvest_level INTEGER DEFAULT 0, confidence_reasons TEXT,
    classifications TEXT, tags TEXT, mod_group TEXT,
    UNIQUE(snapshot_id, item_id)
);
CREATE INDEX IF NOT EXISTS idx_items_mod ON items(mod_id);
CREATE INDEX IF NOT EXISTS idx_items_emc ON items(emc_value);
CREATE TABLE IF NOT EXISTS recipes (
    recipe_id TEXT PRIMARY KEY, recipe_type TEXT NOT NULL,
    mod_id TEXT NOT NULL, ingredients TEXT NOT NULL,
    results TEXT NOT NULL, covered INTEGER DEFAULT 0,
    created_at TEXT DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_recipes_type ON recipes(recipe_type);
"""



class LLMClient:
    """LLM client for EMC value suggestions."""
    DEFAULT_CONFIG = {
        "provider": "deepseek",
        "api_key": "",
        "model": "deepseek-chat",
        "api_url": "https://api.deepseek.com/v1/chat/completions",
        "temperature": 0.3,
    }
    def __init__(self, api_key=None, config_path=None):
        self.config = self._load_config(config_path)
        if api_key:
            self.config["api_key"] = api_key
    def _load_config(self, config_path):
        if config_path and os.path.exists(config_path):
            with open(config_path, "r", encoding="utf-8") as f:
                return {**self.DEFAULT_CONFIG, **json.load(f)}
        default = os.path.join(DEFAULT_DB_DIR, "config.json")
        if os.path.exists(default):
            with open(default, "r", encoding="utf-8") as f:
                return {**self.DEFAULT_CONFIG, **json.load(f)}
        return dict(self.DEFAULT_CONFIG)
    def _build_prompt(self, items, item_meta=None):
        prompt = "You are an EMC expert for Minecraft mods.\nReference values: diamond=8192, iron_ingot=256, gold_ingot=2048, coal=128, stone=1, redstone=64, netherite_ingot=73728.\nRules: tier=3->2048-32768, tier=2->256-1024, tier=1->128-256, common->1-32.\nRespond ONLY with a valid JSON array (no markdown).\nItems:\n"
        return prompt
    def suggest_batch(self, items, item_meta=None):
        if not self.config.get("api_key"):
            print("[EMC] No LLM API key. Use --api-key or set config.")
            return None
        import urllib.request, time
        print(f"[EMC]  → LLM asking for {len(items)} items ({items[0][:40]}...{items[-1][:40]})")
        
        # Try with metadata first
        prompt = self._build_prompt(items, item_meta)
        
        for attempt in range(2):
            body = json.dumps({
                "model": self.config["model"],
                "messages": [{"role": "user", "content": prompt}],
                "temperature": self.config["temperature"],
            }).encode("utf-8")
            try:
                req = urllib.request.Request(
                    self.config["api_url"], data=body,
                    headers={"Content-Type": "application/json",
                             "Authorization": f"Bearer {self.config['api_key']}"},
                    method="POST")
                resp = urllib.request.urlopen(req, timeout=120)
                result = json.loads(resp.read().decode("utf-8"))
                raw = result["choices"][0]["message"]["content"].strip()
                print(f"[EMC]  → Raw: {raw[:300].replace(chr(10),' | ')}")
                if raw.startswith("```"):
                    raw = raw.split("```")[1]
                    if raw.startswith("json"):
                        raw = raw[4:]
                suggestions = []
                # Positional parsing: one value per line
                values = []
                for line in raw.strip().split("\n"):
                    line = line.strip().strip(",").strip("[]").strip()
                    if not line:
                        continue
                    # Try to extract a number from each line
                    import re
                    nums = re.findall(r"\d+", line)
                    if nums:
                        values.append(int(nums[0]))
                if len(values) == len(items):
                    for i, item_id in enumerate(items):
                        suggestions.append({"item_id": item_id, "suggested_emc": values[i], "reasons": ["LLM"]})
                    print(f"[EMC]  ← Positional match: {len(suggestions)}/{len(items)}")
                else:
                    try:
                        parsed = json.loads(raw.strip())
                        if isinstance(parsed, list):
                            for s in parsed:
                                entry = {}
                                if isinstance(s, dict):
                                    entry["item_id"] = s.get("item_id") or s.get("item") or None
                                    entry["suggested_emc"] = s.get("suggested_emc") or s.get("emc") or s.get("value") or None
                                elif isinstance(s, (int, float)):
                                    continue
                                if entry.get("item_id") and entry.get("suggested_emc"):
                                    suggestions.append(entry)
                        elif isinstance(parsed, dict):
                            for k, v in parsed.items():
                                if ":" in str(k):
                                    suggestions.append({"item_id": k, "suggested_emc": int(v) if isinstance(v, (int,float)) else 32})
                    except (json.JSONDecodeError, ValueError):
                        print(f"[EMC]  ✗ Could not parse response as positional or JSON")
                        pass
                if not isinstance(suggestions, list):
                    suggestions = []
                for s in suggestions:
                    s.setdefault("item_id", s.pop("item", None))
                    s.setdefault("suggested_emc", s.pop("emc", s.pop("value", None)))
                if not isinstance(suggestions, list):
                    suggestions = []
                for s in suggestions:
                    s.setdefault("reasons", [s.get("reason", "LLM")])
                    s.setdefault("harvest_level", 0)
                    s.setdefault("is_fuel", False)
                    s.setdefault("burn_time", 0)
                if suggestions:
                    print(f"[EMC]  ← LLM returned {len(suggestions)}/{len(items)} suggestions")
                    return suggestions
                if attempt == 0:
                    print(f"[EMC]  ← retrying...")
                    time.sleep(1)
                else:
                    print(f"[EMC]  ← gave up on this batch")
                    return None
            except json.JSONDecodeError as e:
                print(f"[EMC]  ✗ LLM JSON error: {e}")
                print(f"[EMC]  ✗ Raw response: {raw[:200] if 'raw' in dir() else 'unknown'}")
                if attempt == 0:
                    print("[EMC]  → Retrying with simpler prompt...")
                    prompt = "Assign EMC values. JSON array only.\nItems:\n"
                    for item_id in items:
                        prompt += f"  - {item_id}\n"
                    time.sleep(1)
                else:
                    return None
            except Exception as e:
                print(f"[EMC]  ✗ LLM error: {e}")
                if attempt == 0:
                    print("[EMC]  → Retrying once...")
                    time.sleep(2)
                else:
                    return None
        return None


class EMCClient:
    def __init__(self, db_path=None):
        if db_path is None:
            os.makedirs(DEFAULT_DB_DIR, exist_ok=True)
            db_path = os.path.join(DEFAULT_DB_DIR, "emc_assistant.db")
        self.db_path = db_path
        self._init_db()

    def _init_db(self):
        conn = sqlite3.connect(self.db_path)
        conn.executescript(DB_SCHEMA)
        conn.commit()
        conn.close()

    def _conn(self):
        return sqlite3.connect(self.db_path)

    def import_snapshot(self, snapshot_path):
        with open(snapshot_path, "r", encoding="utf-8") as f:
            data = json.load(f)

        sid = datetime.now().strftime("%Y%m%d_%H%M%S")
        conn = self._conn()
        cur = conn.cursor()
        mod_groups = data.get("mod_groups", {})

        cur.execute("""INSERT OR REPLACE INTO snapshots
            (snapshot_id, mod_count, item_count, items_with_emc,
             items_without_emc, total_mods, projecte_available,
             game_version, mod_version)
            VALUES (?,?,?,?,?,?,?,?,?)""", (
            sid, len(mod_groups), data.get("total_items_scanned", 0),
            data.get("items_with_emc", 0), data.get("items_without_emc", 0),
            len(mod_groups), 1 if data.get("projecte_available") else 0,
            data.get("game_version", "unknown"), data.get("mod_version", "unknown")
        ))

        for mod_id, group in mod_groups.items():
            cur.execute("INSERT OR IGNORE INTO mods (mod_id, mod_name) VALUES (?,?)",
                        (mod_id, group.get("mod_id", mod_id)))

        imported = 0
        for item in data.get("items", []):
            item_id = item.get("registry_name", "")
            mod_id = item_id.split(":")[0] if ":" in item_id else "unknown"
            has_emc = item.get("has_emc", False)
            emc_val = item.get("emc_value", 0) if has_emc else None
            cls = item.get("classifications", {})

            tags_str = json.dumps(item.get("tags", []))
            cls_str = json.dumps(item.get("classifications", {}))
            cur.execute("""INSERT OR REPLACE INTO items
                            (item_id, mod_id, emc_value, source, is_fuel, burn_time, harvest_level,
                             has_producing_recipe, is_high_confidence_raw_material,
                             is_raw_material_candidate, classifications, tags)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", (
                            item_id, mod_id, emc_val,
                            "projecte" if has_emc else None,
                            1 if item.get("is_fuel") else 0,
                            item.get("burn_time", 0), item.get("harvest_level", 0),
                            1 if item.get("has_producing_recipe") else 0,
                            1 if item.get("is_high_confidence_raw_material") else 0,
                            1 if item.get("is_raw_material_candidate") else 0,
                            cls_str, tags_str
                        ))

            cur.execute("""INSERT OR REPLACE INTO snapshot_items
                (snapshot_id, item_id, has_emc, emc_value, has_pei_override,
                 has_producing_recipe, is_fuel, burn_time,
                 is_high_confidence_raw_material, is_raw_material_candidate,
                 harvest_level, confidence_reasons, classifications, tags, mod_group)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", (
                sid, item_id, 1 if has_emc else 0, item.get("emc_value", 0),
                1 if item.get("has_pei_override") else 0,
                1 if cls.get("has_producing_recipe") else 0,
                1 if item.get("is_fuel") else 0, item.get("burn_time", 0),
                1 if item.get("is_high_confidence_raw_material") else 0,
                1 if item.get("is_raw_material_candidate") else 0,
                item.get("harvest_level", 0),
                json.dumps(item.get("confidence_reasons", []), ensure_ascii=False),
                json.dumps(cls, ensure_ascii=False),
                json.dumps(item.get("tags", []), ensure_ascii=False),
                mod_id
            ))
            imported += 1

        conn.commit()
        conn.close()
        print(f"[EMC] Imported {imported} items (snapshot: {sid})")

    def list_items(self, mod_id=None, missing_only=False):
        conn = self._conn()
        cur = conn.cursor()
        q = """SELECT i.item_id, i.mod_id, COALESCE(i.display_name,""),
                      i.emc_value, COALESCE(i.source,"-"), i.is_fuel, i.burn_time
               FROM items i WHERE 1=1"""
        p = []
        if mod_id:
            q += " AND i.mod_id=?"
            p.append(mod_id)
        if missing_only:
            q += " AND i.emc_value IS NULL"
        q += " ORDER BY i.mod_id, i.item_id"
        rows = cur.execute(q, p).fetchall()
        conn.close()
        if not rows:
            print("[EMC] No items found.")
            return
        hdr = f"{'Item ID':<50} {'Mod':<20} {'EMC':<8} {'Source':<10} {'Fuel':<6}"
        print(hdr)
        print("-" * 100)
        for r in rows:
            emc = str(r[3]) if r[3] is not None else "-"
            print(f"{r[0]:<50} {r[1]:<20} {emc:<8} {r[4]:<10} {'Y' if r[5] else 'N':<6}")

    def annotate(self, item_id, emc_value):
        conn = self._conn()
        cur = conn.cursor()
        if not cur.execute("SELECT 1 FROM items WHERE item_id=?", (item_id,)).fetchone():
            print(f"[EMC] Error: '{item_id}' not in DB. Run import first.")
            conn.close()
            return False
        cur.execute("UPDATE items SET emc_value=?, source='manual', updated_at=datetime('now') WHERE item_id=?",
                    (emc_value, item_id))
        conn.commit()
        conn.close()
        print(f"[EMC] Annotated {item_id} -> EMC {emc_value}")
        return True

    def export(self, fmt):
        conn = self._conn()
        items = conn.execute(
            "SELECT item_id, emc_value FROM items WHERE (source='manual' OR source='ai_suggest' OR source='refined' OR source='ai_suggest_llm') AND emc_value IS NOT NULL ORDER BY item_id"
        ).fetchall()
        conn.close()
        if not items:
            print("[EMC] No manually annotated items to export.")
            return
        out = {k: v for k, v in items}
        paths = {
            "projecte": "config/projecte/custom_emc.json",
            "pei": "config/projecte_integration/override/custom_emc.json",
        }
        if fmt in paths:
            base = getattr(self, "_game_dir", os.getcwd())
            full = os.path.join(base, paths[fmt])
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "w", encoding="utf-8") as f:
                json.dump(out, f, indent=2, ensure_ascii=False)
                f.write("\n")
            print(f"[EMC] Exported to {full}")
        elif fmt == "kubejs":
            lines = [
                "// EMC Assistant - Auto-generated KubeJS Script",
                "// Place in kubejs/server_scripts/",
                "",
                "onEvent('item.registry', event => {",
            ]
            for item_id, val in items:
                lines.append(f"    event.create('{item_id}').emc({val})")
            lines.append("});")
            full = os.path.join(os.getcwd(), "kubejs/server_scripts/emc_fix.js")
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "w", encoding="utf-8") as f:
                f.write("\n".join(lines) + "\n")
            print(f"[EMC] Exported to {full}")

    
    def ai_suggest(self, mod_id=None, limit=20, output=None):
        conn = self._conn()
        cur = conn.cursor()

        q = """SELECT i.item_id, i.mod_id, i.harvest_level, i.is_fuel, i.burn_time,
                      COALESCE((SELECT AVG(emc_value) FROM items i2
                                WHERE i2.mod_id = i.mod_id AND i2.emc_value IS NOT NULL), 0) as mod_avg_emc,
                      COALESCE((SELECT AVG(emc_value) FROM items i3
                                WHERE i3.harvest_level = i.harvest_level AND i3.emc_value IS NOT NULL), 0) as tier_avg_emc
               FROM items i
               WHERE i.emc_value IS NULL
        """
        params = []
        if mod_id:
            q += " AND i.mod_id = ?"
            params.append(mod_id)
        q += " ORDER BY i.mod_id, i.item_id LIMIT ?"
        params.append(limit)

        rows = cur.execute(q, params).fetchall()

        # Collect reference EMC values from vanilla
        cur.execute("SELECT item_id, emc_value FROM items WHERE mod_id='minecraft' AND emc_value IS NOT NULL")
        vanilla_emc = dict(cur.fetchall())
        conn.close()

        suggestions = []
        for r in rows:
            item_id, mod, hl, fuel, bt, mod_avg, tier_avg = r
            base = 0
            reasons = []

            # Harvest level based suggestion
            if hl >= 3:
                base = 8192
                reasons.append("挖掘等级3: 参考钻石 8192")
            elif hl == 2:
                base = 256
                reasons.append("挖掘等级2: 参考铁 256")
            elif hl == 1:
                base = 128
                reasons.append("挖掘等级1: 参考石 128")
            elif fuel:
                base = bt // 1600 * 128
                reasons.append(f"燃料: 燃烧{bt}tick ≈ 煤{bt//1600}个")
            else:
                base = 32
                reasons.append("无特殊特征: 基础 32")

            # Mod average adjustment
            if mod_avg > 0 and base == 0:
                base = int(mod_avg)
                reasons.append(f"同Mod平均: {int(mod_avg)}")
            elif tier_avg > 0 and base == 0:
                base = int(tier_avg)
                reasons.append(f"同级平均: {int(tier_avg)}")

            suggestions.append({
                "item_id": item_id,
                "suggested_emc": max(1, base),
                "reasons": reasons,
                "harvest_level": hl,
                "is_fuel": bool(fuel),
                "burn_time": bt,
            })

        out_path = output or f"emc_suggestions_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(suggestions, f, indent=2, ensure_ascii=False)
        print(f"[EMC] Suggested {len(suggestions)} items -> {out_path}")
        print(f"[EMC] Run 'annotate-batch {out_path}' to apply")

    def annotate_batch(self, suggestions_path):
        with open(suggestions_path, "r", encoding="utf-8") as f:
            suggestions = json.load(f)

        conn = self._conn()
        cur = conn.cursor()
        ok = 0
        for s in suggestions:
            item_id = s.get("item_id") or s.get("item") or (list(s.keys())[0] if isinstance(s, dict) and len(s) == 1 else None)
            emc = s.get("suggested_emc") or s.get("emc") or s.get("value") or (list(s.values())[0] if isinstance(s, dict) and len(s) == 1 else None)
            if not item_id or not emc:
                print(f"[EMC]  ✗ Skipping unrecognized format: {str(s)[:80]}")
                continue
            cur.execute("UPDATE items SET emc_value=?, source='ai_suggest', updated_at=datetime('now') WHERE item_id=?",
                        (emc, item_id))
            if cur.rowcount > 0:
                ok += 1
        conn.commit()
        conn.close()
        print(f"[EMC] Annotated {ok} items from {suggestions_path}")

    def auto(self, mod_id=None, all_mods=False, export_format="pei", api_key=None, limit=0):
        """One-click: smart annotation using tags + recipes + harvest_level."""
        print("[EMC] === Auto mode ===")
        conn = self._conn()
        cur = conn.cursor()

        # Tier 1: high-confidence raw materials → rule engine
        q = """SELECT item_id, harvest_level, is_fuel, burn_time, has_producing_recipe,
                      is_high_confidence_raw_material, is_raw_material_candidate, tags
               FROM items WHERE emc_value IS NULL"""
        params = []
        if mod_id:
            q += " AND mod_id = ?"
            params.append(mod_id)
        q += " ORDER BY has_producing_recipe ASC, is_high_confidence_raw_material DESC"
        if limit > 0:
            q += f" LIMIT {limit}"
        rows = cur.execute(q, params).fetchall()
        conn.close()

        if not rows:
            print("[EMC] No missing items found.")
            return
        print(f"[EMC] Processing {len(rows)} items...")

        # Classify
        raw_high, raw_cand, has_recipe, tool_skip = 0, 0, 0, 0
        suggestions = []
        for row in rows:
            item_id, hl, fuel, bt, has_recipe_flag, raw_high_flag, raw_cand_flag, tags_json = row
            tags = json.loads(tags_json) if tags_json else []
            tags_str = " ".join(tags)

            # Skip if has recipe
            if has_recipe_flag:
                has_recipe += 1
                continue

            # Skip if forge:tools, forge:armor
            if "forge:tools" in tags_str or "forge:armor" in tags_str:
                tool_skip += 1
                continue

            # Raw material candidates
            is_raw = raw_high_flag or raw_cand_flag
            has_ore_tag = "forge:ores" in tags_str or "forge:raw_materials" in tags_str
            has_ingot_tag = "forge:ingots" in tags_str

            if is_raw or has_ore_tag or has_ingot_tag:
                base = 32
                reasons = ["基础物品: 对标石 32"]
                if hl >= 3:
                    base = 8192
                    reasons = [f"挖掘等级{hl}: 对标钻石 8192"]
                elif hl == 2:
                    base = 256
                    reasons = [f"挖掘等级{hl}: 对标铁 256"]
                elif hl == 1:
                    base = 128
                    reasons = [f"挖掘等级{hl}: 对标石 128"]
                elif fuel and bt > 0:
                    base = max(1, bt // 1600 * 128)
                    reasons = [f"燃料: {bt}tick"]
                elif has_ore_tag:
                    base = 128
                    reasons = ["矿石类: 基础 128"]
                if is_raw:
                    raw_high += 1
                suggestions.append({"item_id": item_id, "suggested_emc": base,
                                    "reasons": reasons, "harvest_level": hl,
                                    "is_fuel": bool(fuel), "burn_time": bt})
            else:
                raw_cand += 1
                suggestions.append({"item_id": item_id, "suggested_emc": 32,
                                    "reasons": ["未分类物品: 基础 32"], "harvest_level": hl,
                                    "is_fuel": bool(fuel), "burn_time": bt})

        # Apply
        conn = self._conn()
        cur = conn.cursor()
        ok = 0
        for s in suggestions:
            cur.execute("UPDATE items SET emc_value=?, source='ai_suggest', updated_at=datetime('now') WHERE item_id=?",
                        (s["suggested_emc"], s["item_id"]))
            if cur.rowcount > 0:
                ok += 1
        conn.commit()
        conn.close()
        print(f"[EMC] Summary: {raw_high} raw_materials + {raw_cand} unclassified + {has_recipe} recipe_skipped + {tool_skip} tool_skipped")
        print(f"[EMC] Annotated {ok}/{len(suggestions)} items")
        
        try:
            self.export(export_format)
        except Exception as e:
            print(f"[EMC] Export: {e}")
        print("[EMC] === Auto complete ===")
    def stats(self):
        conn = self._conn()
        cur = conn.cursor()
        total = cur.execute("SELECT COUNT(*) FROM items").fetchone()[0]
        with_emc = cur.execute("SELECT COUNT(*) FROM items WHERE emc_value IS NOT NULL").fetchone()[0]
        missing = cur.execute("SELECT COUNT(*) FROM items WHERE emc_value IS NULL").fetchone()[0]
        manual = cur.execute("SELECT COUNT(*) FROM items WHERE source IN ('manual','ai_suggest','refined','ai_suggest_llm')").fetchone()[0]
        mod_cnt = cur.execute("SELECT COUNT(*) FROM mods").fetchone()[0]
        snap_cnt = cur.execute("SELECT COUNT(*) FROM snapshots").fetchone()[0]
        print("=== EMC Database Stats ===")
        print(f"  Total items: {total}")
        print(f"  With EMC:    {with_emc}")
        print(f"  Missing EMC: {missing}")
        print(f"  Annotated(manual+AI): {manual}")
        print(f"  Mods: {mod_cnt}")
        print(f"  Snapshots: {snap_cnt}")
        print()
        print(f"  {'Mod':<20} {'Total':<8} {'Covered':<8}")
        print("  " + "-" * 36)
        for r in cur.execute("SELECT mod_id,COUNT(*) as c, SUM(CASE WHEN emc_value IS NOT NULL THEN 1 ELSE 0 END) as cov FROM items GROUP BY mod_id ORDER BY c DESC LIMIT 10"):
            print(f"  {r[0]:<20} {r[1]:<8} {r[2]:<8}")

    def import_recipes(self, recipes_list):
        """M1：导入 snapshot.recipes 到 recipes 表."""
        if not recipes_list:
            print("[REC] snapshot 无 recipes 数据（需 P1 升级版重新扫描）")
            return 0
        conn = self._conn()
        cur = conn.cursor()
        n = 0
        for r in recipes_list:
            rid = r.get("id")
            if not rid:
                continue
            rtype = r.get("type", "")
            mod = r.get("mod") or (rtype.split(":")[0] if rtype else "unknown")
            ingredients = json.dumps(r.get("ingredients", []), ensure_ascii=False)
            results = json.dumps(r.get("output", {}), ensure_ascii=False)
            covered = 1 if r.get("covered") else 0
            cur.execute(
                "INSERT OR REPLACE INTO recipes (recipe_id, recipe_type, mod_id, ingredients, results, covered) VALUES (?,?,?,?,?,?)",
                (rid, rtype, mod, ingredients, results, covered))
            n += 1
        conn.commit()
        conn.close()
        print(f"[REC] 导入 {n} 条配方")
        return n

    def gap_report(self, limit=15):
        """M1：配方缺口报告（recipeType 分布 + ProjectE 覆盖判定）."""
        conn = self._conn()
        cur = conn.cursor()
        row = cur.execute("SELECT COUNT(*), COALESCE(SUM(covered),0) FROM recipes").fetchone()
        total, covered = (row[0] or 0), (row[1] or 0)
        print("=== 配方缺口报告 ===")
        print(f"  配方总数: {total} | ProjectE 默认覆盖: {covered} | 未覆盖: {total - covered}")
        print()
        print(f"  未覆盖 recipeType Top {limit}（翻译器目标）:")
        for r in cur.execute("SELECT recipe_type, COUNT(*) as c FROM recipes WHERE covered=0 GROUP BY recipe_type ORDER BY c DESC LIMIT ?", (limit,)):
            print(f"    {r[0]}: {r[1]}")
        print()
        print("  未覆盖 type 涉及 mod 分布:")
        for r in cur.execute("SELECT mod_id, COUNT(DISTINCT recipe_type) as types FROM recipes WHERE covered=0 GROUP BY mod_id ORDER BY types DESC LIMIT 12"):
            print(f"    {r[0]}: {r[1]} 种 recipeType")
        conn.close()
        conn.close()



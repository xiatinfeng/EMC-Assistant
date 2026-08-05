#!/usr/bin/env python3
"""EMC Assistant - 简化精简界面. 选文件夹 → 两个选项 → 自动写回."""

import os, sys, json, glob, shutil, sqlite3
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from emc_assistant.emc_assistant import EMCClient, DEFAULT_DB_DIR

CONFIG_PATH = os.path.join(DEFAULT_DB_DIR, "config.json")
# P1 Mod jar: 放在 exe 同目录下，命名 EMCAssistant.jar 即可
if getattr(sys, 'frozen', False):
    _exe_dir = os.path.dirname(sys.executable)
else:
    _exe_dir = os.path.dirname(os.path.abspath(__file__))
P1_JARS = sorted(glob.glob(os.path.join(_exe_dir, "EMCAssistant*.jar"))) or           sorted(glob.glob(os.path.join(_exe_dir, "*.jar")))
P1_JAR = P1_JARS[0] if P1_JARS else None

def load_config():
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH) as f:
            return json.load(f)
    return {}

def save_config(cfg):
    os.makedirs(DEFAULT_DB_DIR, exist_ok=True)
    with open(CONFIG_PATH, "w") as f:
        json.dump(cfg, f, indent=2)

def folder_picker():
    """Tkinter folder browser — 简化."""
    try:
        import tkinter as tk
        from tkinter import filedialog
        root = tk.Tk()
        root.withdraw()
        root.attributes("-topmost", True)
        folder = filedialog.askdirectory(title="选择整合包版本文件夹")
        root.destroy()
        return folder
    except:
        return input("手动输入整合包版本文件夹路径: ").strip()

def detect_modpack():
    """检测上次用的整合包路径，或让用户选."""
    cfg = load_config()
    folder = cfg.get("last_folder", "")
    if folder and os.path.exists(folder):
        print(f"检测到上次路径: {folder}")
        r = input("使用此路径? (Y/n): ").strip().lower()
        if r != "n":
            return folder
    print("\n请选择整合包版本文件夹...")
    folder = folder_picker()
    if folder:
        cfg["last_folder"] = folder
        save_config(cfg)
    return folder

def ensure_api_key():
    """确保有 API Key（用于 LLM 精调）。"""
    cfg = load_config()
    key = cfg.get("api_key", "")
    if key:
        return key
    print("\n[!] 未配置 API Key，大模型精调不可用")
    r = input("是否现在配置? (y/N): ").strip().lower()
    if r == "y":
        return first_run_wizard()
    return None

def check_snapshot(folder):
    """检测 snapshot 是否存在，不存在则部署 P1 mod."""
    snap_dir = os.path.join(folder, ".emc_assistant")
    snap_file = os.path.join(snap_dir, "items_snapshot.json")
    if os.path.exists(snap_file):
        return snap_file
    # 没有 → 部署 P1 mod
    print(f"\n未检测到 snapshot 文件 ({snap_file})")
    print("需要在游戏内由 P1 Mod 扫描生成。")
    mods_dir = os.path.join(folder, "mods")
    if not os.path.exists(mods_dir):
        os.makedirs(mods_dir)
    if os.path.exists(P1_JAR):
        shutil.copy2(P1_JAR, os.path.join(mods_dir, "EMCAssistant.jar"))
        print(f"✅ P1 Mod 已部署到: {mods_dir}")
        print("请启动整合包进入游戏，等待约 10 秒后退出。")
        print("snapshot 将自动生成在 .emc_assistant/ 目录下。")
    else:
        print("⚠️ 未找到 P1 Mod 文件 (EMCAssistant.jar)")
        print("请将 EMCAssistant.jar 放到本程序同目录下，或手动放入 mods 文件夹。")
    input("\n准备好了? 启动游戏并退出后，按 Enter 继续...")
    if os.path.exists(snap_file):
        return snap_file
    print("仍未检测到 snapshot，请手动导入。")
    return None

def import_and_prep(folder):
    """导入 snapshot 到 DB."""
    snap_file = check_snapshot(folder)
    if not snap_file:
        return False
    print(f"\n导入 snapshot: {snap_file}")
    client = EMCClient()
    client._game_dir = folder  # 导出路径指向游戏目录
    client.import_snapshot(snap_file)
    try:
        import json as _json
        with open(snap_file, "r", encoding="utf-8") as _f:
            _snap = _json.load(_f)
        client.import_recipes(_snap.get("recipes", []))
        client.gap_report()
    except Exception as e:
        print(f"⚠️ 配方导入失败（P1 为旧版可忽略）: {e}")
    return client

def export_to_game(client):
    """自动导出到游戏配置目录."""
    print("\n正在写入游戏配置文件...")
    try:
        client.export("pei")
        print("✅ 配置已写入游戏 config/projecte_integration/override/")
    except Exception as e:
        print(f"⚠️ 导出失败: {e}")

def first_run_wizard():
    """首次运行：配置 LLM 提供商与 Key，生成 config.json."""
    print("\n首次使用，请配置大模型 API。\n")
    print("支持的提供商:")
    print("  [1] DeepSeek (推荐，便宜快速)")
    print("  [2] SiliconFlow (硅基流动)")
    print("  [3] 其他 (自定义 URL)")
    pick = input("请选择 (默认 1): ").strip()
    
    providers = {
        "1": {"model": "deepseek-v4-flash", "url": "https://api.deepseek.com/v1/chat/completions"},
        "2": {"model": "deepseek-ai/DeepSeek-V4-Flash", "url": "https://api.siliconflow.cn/v1/chat/completions"},
    }
    provider = providers.get(pick, providers["1"])
    
    if pick == "3":
        provider["url"] = input("API URL: ").strip()
        provider["model"] = input("模型名: ").strip()
    
    key = input("\nAPI Key: ").strip()
    try:
        conc = int(input("并发数 (默认 50, 越大越快但费API额度): ").strip() or "50")
    except:
        conc = 50
    try:
        bsize = int(input("每批物品数 (默认 20): ").strip() or "20")
    except:
        bsize = 20
    cfg = {
        "api_key": key,
        "model": provider["model"],
        "api_url": provider["url"],
        "temperature": 0.3,
        "concurrency": conc,
        "batch_size": bsize,
    }
    save_config(cfg)
    print("\n✅ 配置已保存到:", CONFIG_PATH)
    print("  可在 config.json 中修改")
    return cfg.get("api_key")

def main():
    print("=" * 50)
    print("   EMC Assistant — EMC自动标注工具")
    print("   一键流程：选文件夹 → 标记EMC → 自动写回游戏配置")
    print("=" * 50)

    # 首次运行检测
    cfg = load_config()
    if not cfg.get("api_key"):
        key = first_run_wizard()
        if not key:
            print("未配置 API Key，将仅使用本地规则引擎。")
        cfg = load_config()

    # Step 1: 选整合包文件夹
    last_folder = cfg.get("last_folder", "")
    folder = detect_modpack()
    if not folder:
        print("未选择文件夹，退出。")
        input("按 Enter 退出...")
        return
    print(f"整合包: {folder}")

    # 切换了整合包 → 清空旧缓存
    if last_folder and last_folder != folder:
        print("\n⚠️ 检测到整合包切换，清理旧缓存...")
        db_path = os.path.join(DEFAULT_DB_DIR, "emc_assistant.db")
        if os.path.exists(db_path):
            os.remove(db_path)
            print("  ✅ 旧数据库已删除")
        # 清理旧导出文件
        for old_dir in ["config/projecte", "config/projecte_integration"]:
            old_path = os.path.join(os.getcwd(), old_dir)
            if os.path.exists(old_path):
                import shutil
                shutil.rmtree(old_path)
                print(f"  ✅ 旧导出已清理: {old_dir}")
        print("  准备就绪，将重新导入。")

    # Step 2: 检测/导入 snapshot
    print("\n--- 检测游戏数据 ---")
    from emc_assistant.emc_assistant import EMCClient
    # 清理旧 ai_suggest 标注以便重新跑
    db_path = os.path.join(DEFAULT_DB_DIR, "emc_assistant.db")
    if os.path.exists(db_path):
        conn = sqlite3.connect(db_path)
        conn.execute("UPDATE items SET emc_value = NULL, source = NULL WHERE source IN ('ai_suggest','refined','ai_suggest_llm')")
        conn.commit()
        conn.close()

    snap_file = check_snapshot(folder)
    if not snap_file:
        return
    print(f"  导入: {snap_file}")
    client = EMCClient()
    client._game_dir = folder
    client.import_snapshot(snap_file)
    client.stats()

    # Step 3: 两个选项
    print("\n" + "=" * 50)
    print("选择标记方式:")
    print("  [1] 本地规则引擎标记（秒级完成，按挖掘等级/燃料估算）")
    print("  [2] 大模型精调标记（规则引擎 + LLM 精调，约 2 分钟，更准确）")
    print("  [0] 退出")
    print("-" * 50)
    choice = input("请选择: ").strip()

    if choice == "1":
        print("\n--- 本地规则引擎标记 ---")
        client.auto(all_mods=True)
        export_to_game(client)

    elif choice == "2":
        print("\n--- 大模型精调标记 ---")
        key = ensure_api_key()
        if not key:
            print("无 API Key，降级为本地规则引擎标记。")
            client.auto(all_mods=True)
            export_to_game(client)
            return
        # 先规则引擎
        print("\n[阶段 1/2] 规则引擎基础标注...")
        client.auto(all_mods=True)
        # 再 LLM 精调
        print("\n[阶段 2/2] 大模型精调 (50并发)...")
        from emc_assistant.menu import refine_deep
        # 直接调用 refine 逻辑
        import urllib.request, re, threading
        from concurrent.futures import ThreadPoolExecutor, as_completed
        cfg = load_config()
        conn = sqlite3.connect(db_path)
        rows = conn.execute(
            "SELECT item_id FROM items WHERE source='ai_suggest' AND emc_value=32 ORDER BY mod_id"
        ).fetchall()
        conn.close()
        all_items = [r[0] for r in rows]
        if not all_items:
            print("  无需精调，全部已有合理值。")
        else:
            print(f"  精调 {len(all_items)} 个物品...")
            cfg_v = load_config()
            BATCH = cfg_v.get("batch_size", 20)
            CONC = cfg_v.get("concurrency", 50)
            batches = [all_items[i:i+BATCH] for i in range(0, len(all_items), BATCH)]
            lock = threading.Lock()
            results = []
            def process(batch):
                prompt = "EMC: common=32, uncommon=128, rare=512, very_rare=4096, epic=32768. Reply EMC per line, same order.\nItems:\n"
                for it in batch:
                    prompt += f"  {it}\n"
                body = json.dumps({"model": cfg["model"], "messages": [{"role": "user", "content": prompt}], "temperature": 0.1}).encode()
                try:
                    req = urllib.request.Request(cfg["api_url"], data=body,
                        headers={"Content-Type": "application/json", "Authorization": f"Bearer {cfg['api_key']}"},
                        method="POST")
                    resp = urllib.request.urlopen(req, timeout=120)
                    raw = json.loads(resp.read())["choices"][0]["message"]["content"].strip()
                    nums = re.findall(r'\d+', raw)
                    local = []
                    if len(nums) == len(batch):
                        for i, item_id in enumerate(batch):
                            v = int(nums[i])
                            if v > 32:
                                local.append((item_id, v))
                    with lock:
                        results.extend(local)
                except:
                    pass
            with ThreadPoolExecutor(max_workers=CONC) as ex:
                for f in as_completed([ex.submit(process, b) for b in batches]):
                    pass
            conn = sqlite3.connect(db_path)
            cur = conn.cursor()
            ok = 0
            for item_id, emc in results:
                cur.execute("UPDATE items SET emc_value=?, source='refined', updated_at=datetime('now') WHERE item_id=? AND emc_value=32",
                           (emc, item_id))
                if cur.rowcount > 0:
                    ok += 1
            conn.commit()
            conn.close()
            print(f"  精调完成: {ok}/{len(results)} 个已更新")
        export_to_game(client)
    else:
        print("退出。")
        return

    print("\n" + "=" * 50)
    print("✅ 全部完成！")
    client.stats()
    print("=" * 50)
    print("提示:")
    print("  重新启动游戏后，EMC 值将自动生效。")
    print("  如需重新标记，再次运行本工具即可。")
    input("\n按 Enter 退出...")

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n已取消。")
    except Exception as e:
        print(f"\n错误: {e}")
        import traceback
        traceback.print_exc()
        input("\n按 Enter 退出...")
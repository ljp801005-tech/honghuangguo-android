#!/usr/bin/env python3
"""按 App 内 SiteRepository 的抓取流程探活：首页 -> 详情 -> HLS 播放地址 -> 分片。

用法: python3 tools/resource_probe.py [--origin https://huangguoai.com]
配置来源: app/src/main/assets/site-default.json（与 App 内置的一致）
"""
import json
import re
import ssl
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import html as html_unescape_mod
from pathlib import Path

from bs4 import BeautifulSoup


def html_unescape(value):
    return html_unescape_mod.unescape(value)

class Blocked(BaseException):
    """出口被限制（TLS 被重置 / 连接被拒）。继承 BaseException，避免被各步的 except Exception 吞掉。"""


BLOCK_CODES = {7, 35, 52, 56}   # curl: 连接失败 / TLS 握手失败 / 空响应 / 接收失败

ROOT = Path(__file__).resolve().parent.parent
CFG = json.loads((ROOT / "app/src/main/assets/site-default.json").read_text(encoding="utf-8"))
ORIGIN = sys.argv[sys.argv.index("--origin") + 1] if "--origin" in sys.argv else CFG["origin"]
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def headers(extra=None):
    out = {}
    for k, v in CFG.get("headers", {}).items():
        out[k] = v.replace("{origin}", ORIGIN)
    out.update(extra or {})
    return out


def get(url, extra_headers=None, timeout=25, attempts=3):
    """用 curl 取页面：Python 的 urllib 直连该站会被 TLS 掐断，curl 走系统代理更稳。"""
    last = None
    for i in range(attempts):
        cmd = ["curl", "-sS", "-L", "--compressed", "--max-time", str(timeout),
               "-w", "\n%{http_code}\t%{content_type}\t%{time_total}"]
        for key, value in headers(extra_headers).items():
            cmd += ["-H", f"{key}: {value}"]
        cmd.append(url)
        proc = subprocess.run(cmd, capture_output=True)
        if proc.returncode == 0:
            raw = proc.stdout
            head, _, tail = raw.rpartition(b"\n")
            parts = tail.decode("utf-8", "replace").split("\t")
            if len(parts) == 3:
                status, ctype, seconds = int(parts[0]), parts[1], float(parts[2])
                if status > 0:
                    return status, ctype, head, seconds * 1000
        last = proc.stderr.decode("utf-8", "replace").strip() or f"curl exit {proc.returncode}"
        if proc.returncode in BLOCK_CODES:
            raise Blocked(last)
        time.sleep(1.5 * (i + 1))
    raise RuntimeError(last)


def route(name, **kw):
    path = CFG["routes"][name]
    for key, value in kw.items():
        path = path.replace("{%s}" % key, str(value))
    return ORIGIN + path


def cards(html):
    soup = BeautifulSoup(html, "html.parser")
    sel = CFG["selectors"]
    items = []
    seen = set()
    for card in soup.select(sel["cards"]):
        tid = card.get("data-track-id", "")
        if not re.fullmatch(r"\d+", tid) or tid in seen:
            continue
        seen.add(tid)
        title_node = card.select_one(sel["title"])
        title = title_node.get_text(" ", strip=True) if title_node else ""
        cover_node = card.select_one(sel["cover"])
        cover = ""
        if cover_node:
            cover = cover_node.get("data-src") or cover_node.get("src") or ""
        items.append({"id": tid, "title": title, "cover": cover})
    return items


def main():
    report = []

    def step(name, ok, detail):
        report.append((name, ok, detail))
        print(("PASS  " if ok else "FAIL  ") + name + "  " + detail)

    # 1. 首页
    try:
        status, ctype, body, ms = get(route("home"))
        html = body.decode("utf-8", "replace")
        home_cards = cards(html)
        soup = BeautifulSoup(html, "html.parser")
        hero = soup.select_one(CFG["selectors"]["hero"])
        hero_n = 0
        if hero is not None:
            raw = hero.get("data-hero-slides") or hero.get_text(strip=True)
            try:
                hero_n = len(json.loads(raw))
            except Exception:
                hero_n = 0
        step("首页 HTML", status == 200 and len(body) > 1000, f"HTTP {status}, {len(body)/1024:.0f} KB, {ms:.0f} ms")
        step("首页剧集卡片", len(home_cards) > 0, f"解析到 {len(home_cards)} 张卡片, 轮播 {hero_n} 条, 例: "
             + (f"{home_cards[0]['id']} {home_cards[0]['title'][:18]}" if home_cards else "-"))
    except Exception as err:
        step("首页 HTML", False, repr(err))
        print("\n首页就失败，后续跳过")
        return 1

    # 2. 精选推荐 / 最近上新（App 首页会并发抓这两页）
    for name in ("recommend", "newest"):
        try:
            status, _, body, ms = get(route(name))
            n = len(cards(body.decode("utf-8", "replace")))
            step(f"{name} 列表页", status == 200 and n > 0, f"HTTP {status}, {n} 张卡片, {ms:.0f} ms")
        except Exception as err:
            step(f"{name} 列表页", False, repr(err))

    # 3. 搜索
    try:
        keyword = home_cards[0]["title"][:4] or "剧"
        url = route("search", query=urllib.parse.quote(keyword))
        status, _, body, ms = get(url)
        n = len(cards(body.decode("utf-8", "replace")))
        step("搜索页", status == 200, f"关键词「{keyword}」HTTP {status}, {n} 张卡片, {ms:.0f} ms")
    except Exception as err:
        step("搜索页", False, repr(err))

    # 4. 详情页 -> videoInitialData
    video_src = ""
    detail_id = home_cards[0]["id"]
    try:
        status, _, body, ms = get(route("detail", id=detail_id))
        html = body.decode("utf-8", "replace")
        node = BeautifulSoup(html, "html.parser").select_one(CFG["selectors"]["content"])
        data = {}
        if node is not None:
            raw = node.get("data-initial") or node.get_text(strip=True)
            data = json.loads(raw)
        video_src = data.get("videoSrc", "")
        step("详情页数据", status == 200 and bool(data.get("id")),
             f"id={data.get('id')} 标题={str(data.get('title'))[:20]} 标签={len(data.get('tags') or [])} {ms:.0f} ms")
        step("服务端直出播放地址", bool(video_src),
             (video_src or "服务端 HTML 里 videoSrc 为空，需页面 JS 运行时填充（原生抓取路径会失败）")[:110])
    except Exception as err:
        step("详情页数据", False, repr(err))

    # 4.5 用无头浏览器跑页面 JS，拿到真实播放地址（等价于 WebView 内真实环境）
    if not video_src:
        chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
        if Path(chrome).exists():
            try:
                import subprocess
                import tempfile
                with tempfile.NamedTemporaryFile(suffix=".html", delete=False) as tmp:
                    dom_path = tmp.name
                subprocess.run([chrome, "--headless=new", "--no-sandbox", "--disable-gpu",
                                "--virtual-time-budget=20000", "--dump-dom", route("detail", id=detail_id)],
                               stdout=open(dom_path, "w"), stderr=subprocess.DEVNULL, timeout=90)
                dom = Path(dom_path).read_text(encoding="utf-8", errors="replace")
                Path(dom_path).unlink(missing_ok=True)
                hit = re.search(r'<source[^>]+src="([^"]+)"', dom)
                if hit:
                    video_src = html_unescape(hit.group(1))
                    step("浏览器渲染后播放地址", True, video_src[:110])
                elif re.search(r'id="videoInitialData"[^>]*>(.*?)</script>', dom, re.S):
                    raw = re.search(r'id="videoInitialData"[^>]*>(.*?)</script>', dom, re.S).group(1)
                    if json.loads(raw).get("videoSrc"):
                        video_src = json.loads(raw)["videoSrc"]
                        step("浏览器渲染后播放地址", True, video_src[:110])
                    else:
                        step("浏览器渲染后播放地址", False, "页面 JS 执行后仍未生成播放地址（可能需要年龄确认或登录）")
                else:
                    step("浏览器渲染后播放地址", False, "渲染结果里没有 video 元素")
            except Exception as err:
                step("浏览器渲染后播放地址", False, repr(err))
        else:
            step("浏览器渲染后播放地址", False, "本机没有 Chrome，跳过")

    # 5. HLS 清单
    if video_src:
        try:
            src = urllib.parse.urljoin(ORIGIN + "/", video_src)
            status, ctype, body, ms = get(src, {"Accept": "*/*"})
            text = body.decode("utf-8", "replace")
            is_m3u = text.lstrip().startswith("#EXTM3U")
            step("HLS 主清单", status == 200 and is_m3u,
                 f"HTTP {status}, {ctype}, {len(body)} B, {'#EXTM3U 正常' if is_m3u else '不是 m3u8'}, {ms:.0f} ms")

            lines = [l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]
            if lines:
                ref = urllib.parse.urljoin(src, lines[0])
                status2, ctype2, body2, ms2 = get(ref, {"Accept": "*/*"})
                sub = body2.decode("utf-8", "replace")
                if sub.lstrip().startswith("#EXTM3U"):
                    segs = [l.strip() for l in sub.splitlines() if l.strip() and not l.startswith("#")]
                    if segs:
                        seg_url = urllib.parse.urljoin(ref, segs[0])
                        s3, c3, b3, ms3 = get(seg_url, {"Accept": "*/*", "Range": "bytes=0-262143"})
                        step("视频分片", s3 in (200, 206) and len(b3) > 1000,
                             f"HTTP {s3}, {c3}, {len(b3)/1024:.0f} KB, {ms3:.0f} ms, 共 {len(segs)} 片")
                    else:
                        step("视频分片", False, "子清单里没有分片")
                else:
                    step("视频分片", status2 in (200, 206),
                         f"单个媒体流 HTTP {status2}, {ctype2}, {len(body2)/1024:.0f} KB, {ms2:.0f} ms")
        except Exception as err:
            step("HLS 主清单", False, repr(err))

    ok = sum(1 for _, flag, _ in report if flag)
    print(f"\n共 {len(report)} 项，通过 {ok} 项，失败 {len(report) - ok} 项")
    return 0 if ok == len(report) else 2


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Blocked as err:
        print("\n判定：出口被限制（TLS 被重置/连接被拒），已停止后续探测。")
        print(f"  详情：{err}")
        print("  处理：1) 换 Clash 节点或换网络  2) 给该域名加 DIRECT 分流  3) 冷却 10 分钟再试")
        print("  别在这期间反复重试——重试会持续命中限流窗口，等于自己续期。")
        sys.exit(3)

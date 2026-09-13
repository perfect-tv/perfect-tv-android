#!/usr/bin/env python3
"""Поиск каналов плейлиста, в которых реально идёт DVB-телетекст.

Для каждого канала берёт HLS-плейлист (или сырой поток MPEG-TS), качает несколько
сегментов, разбирает PAT/PMT и ищет дорожки с дескриптором телетекста (0x56) или
VBI-телетекста (0x46). Затем считает пакеты этой дорожки и вынимает номера страниц
из заголовков (строка 0). Так видно разницу между каналами, которые телетекст
только объявляют (PMT есть, пакетов нет), и теми, где данные идут по-настоящему.

Только стандартная библиотека. Примеры:

    tools/find_teletext.py playlist.m3u
    tools/find_teletext.py https://example.com/list.m3u --filter "первый" --workers 16
    tools/find_teletext.py playlist.m3u --all --segments 4 > report.txt
    tools/find_teletext.py playlist.m3u --filter "НТВ" --save-dir /tmp/ttx   # образцы для тестов

Ссылки каналов часто содержат логин и пароль: отчёт печатает только названия
каналов, адреса в вывод не попадают.
"""
import argparse
import os
import re
import sys
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

UA = "Mozilla/5.0 (Linux; Android 11; TV) PerfectTV-teletext-probe"
MAX_RAW_BYTES = 3 * 1024 * 1024  # сколько читать из потока, который не HLS

REV = [int("{:08b}".format(i)[::-1], 2) for i in range(256)]


def fetch(url, timeout, limit=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(limit) if limit else r.read()


def parse_m3u(text):
    """Список (название, адрес) из M3U."""
    out, name = [], None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("#EXTINF"):
            name = line.split(",", 1)[1].strip() if "," in line else ""
        elif line and not line.startswith("#"):
            out.append((name or line, line))
            name = None
    return out


def hls_segments(url, timeout, count):
    """Адреса первых count сегментов; главный плейлист раскрывается до первого варианта."""
    for _ in range(3):
        body = fetch(url, timeout, 2 * 1024 * 1024)
        if not body.startswith(b"#EXTM3U"):
            return None  # не HLS — сырой поток
        lines = body.decode("utf-8", "replace").splitlines()
        variants = [
            urllib.parse.urljoin(url, lines[i + 1].strip())
            for i, l in enumerate(lines[:-1])
            if l.startswith("#EXT-X-STREAM-INF")
        ]
        if variants:
            url = variants[0]
            continue
        segs = [urllib.parse.urljoin(url, l.strip()) for l in lines if l.strip() and not l.startswith("#")]
        return segs[:count]
    return []


def get_ts(url, timeout, count):
    segs = hls_segments(url, timeout, count)
    if segs is None:
        return fetch(url, timeout, MAX_RAW_BYTES)
    data = b""
    for s in segs:
        data += fetch(s, timeout)
    return data


def sync_offset(d):
    for off in range(188):
        if all(d[off + 188 * k:off + 188 * k + 1] == b"\x47" for k in range(5)):
            return off
    return None


def ham(b):
    """Hamming 8/4 без исправления ошибок (байт уже в прямом порядке битов)."""
    return ((b >> 1) & 1) | (((b >> 3) & 1) << 1) | (((b >> 5) & 1) << 2) | (((b >> 7) & 1) << 3)


def teletext_descriptor(desc):
    """Записи дескриптора 0x56 или 0x46: [(язык, тип, страница)]."""
    out, i = [], 0
    while i + 2 <= len(desc):
        tag, ln = desc[i], desc[i + 1]
        body = desc[i + 2:i + 2 + ln]
        if tag in (0x56, 0x46):
            for j in range(0, len(body) - 4, 5):
                lang = body[j:j + 3].decode("latin1")
                typ, mag = body[j + 3] >> 3, body[j + 3] & 7
                page = "%d%02X" % (mag or 8, body[j + 4])
                out.append((lang, typ, page))
        i += 2 + ln
    return out


def analyse(d):
    off = sync_offset(d)
    if off is None:
        return None
    pmt_pids, es = set(), {}
    pkts = {}
    pes = {}  # pid -> bytearray текущего PES
    pages = {}
    raw = []  # PES-пакеты телетекста целиком (для --save-dir)
    for i in range(off, len(d) - 187, 188):
        p = d[i:i + 188]
        if p[0] != 0x47:
            continue
        pid = ((p[1] & 0x1F) << 8) | p[2]
        pusi = p[1] & 0x40
        pkts[pid] = pkts.get(pid, 0) + 1
        af = (p[3] >> 4) & 3
        o = 4
        if af & 2:
            o += 1 + p[4]
        if not af & 1:
            continue
        pl = p[o:]
        if pid == 0 and pusi:
            pl = pl[1 + pl[0]:]
            for j in range(8, 3 + (((pl[1] & 0xF) << 8) | pl[2]) - 4, 4):
                if (pl[j] << 8 | pl[j + 1]) != 0:
                    pmt_pids.add(((pl[j + 2] & 0x1F) << 8) | pl[j + 3])
        elif pid in pmt_pids and pusi:
            pl = pl[1 + pl[0]:]
            sl = ((pl[1] & 0xF) << 8) | pl[2]
            j = 12 + (((pl[10] & 0xF) << 8) | pl[11])
            end = 3 + sl - 4
            while j + 5 <= end:
                st = pl[j]
                ep = ((pl[j + 1] & 0x1F) << 8) | pl[j + 2]
                il = ((pl[j + 3] & 0xF) << 8) | pl[j + 4]
                desc = teletext_descriptor(bytes(pl[j + 5:j + 5 + il]))
                if desc:
                    es[ep] = desc
                j += 5 + il
        elif pid in es:
            buf = pes.setdefault(pid, bytearray())
            if pusi and buf:
                read_pes(bytes(buf), pages)
                raw.append(bytes(buf))
                buf.clear()
            buf += pl
    for pid, buf in pes.items():
        if buf:
            read_pes(bytes(buf), pages)
            raw.append(bytes(buf))
    return es, pkts, pages, raw


def read_pes(b, pages):
    """Страницы из заголовков (строка 0) пакетов телетекста в PES."""
    if len(b) < 9 or b[:3] != b"\x00\x00\x01":
        return
    i = 9 + b[8]
    if i >= len(b) or not 0x10 <= b[i] <= 0x1F:  # data_identifier EBU
        return
    i += 1
    while i + 2 <= len(b):
        uid, ln = b[i], b[i + 1]
        u = b[i + 2:i + 2 + ln]
        i += 2 + ln
        if uid in (0x02, 0x03) and ln == 44 and u[1] == 0xE4:
            mag_row = ham(REV[u[2]]) | (ham(REV[u[3]]) << 4)
            mag, row = (mag_row & 7) or 8, mag_row >> 3
            if row == 0:
                units, tens = ham(REV[u[4]]), ham(REV[u[5]])
                if units <= 9 and tens <= 9:
                    pages[mag * 100 + tens * 10 + units] = pages.get(mag * 100 + tens * 10 + units, 0) + 1


def probe(name, url, timeout, count, save_dir=None):
    try:
        res = analyse(get_ts(url, timeout, count))
    except Exception as e:  # сеть, разбор — канал просто пропускаем
        return name, "error", str(e)[:80], None
    if res is None:
        return name, "error", "не MPEG-TS", None
    es, pkts, pages, raw = res
    if not es:
        return name, "none", "", None
    pid, desc = next(iter(es.items()))
    n = pkts.get(pid, 0)
    if save_dir and raw:
        fn = re.sub(r"[^\w+-]+", "_", name).strip("_") or "channel"
        with open(os.path.join(save_dir, fn + ".ttx"), "wb") as f:
            for b in raw:  # формат: длина (4 байта, big-endian) + PES
                f.write(len(b).to_bytes(4, "big") + b)
    info = {
        "pid": pid,
        "packets": n,
        "declared": ", ".join("%s %s%s" % (p, {1: "нач.", 2: "субт.", 5: "субт.(слабослыш.)"}.get(t, "тип%d" % t),
                                          "/" + l if l else "") for l, t, p in desc),
        "pages": sorted(pages),
    }
    return name, "data" if n else "empty", "", info


def main():
    ap = argparse.ArgumentParser(description="Поиск каналов с настоящим DVB-телетекстом")
    ap.add_argument("playlist", help="файл или адрес M3U")
    ap.add_argument("--filter", help="только каналы, в названии которых есть эта подстрока")
    ap.add_argument("--segments", type=int, default=3, help="сколько сегментов HLS качать (3)")
    ap.add_argument("--workers", type=int, default=8, help="параллельных проверок (8)")
    ap.add_argument("--timeout", type=int, default=15, help="таймаут запроса, с (15)")
    ap.add_argument("--limit", type=int, default=0, help="проверить только первые N каналов")
    ap.add_argument("--save-dir", help="сохранять PES-пакеты телетекста найденных каналов в эту папку (*.ttx)")
    ap.add_argument("--all", action="store_true", help="показывать и каналы с пустым телетекстом, и ошибки")
    a = ap.parse_args()

    if re.match(r"https?://", a.playlist):
        text = fetch(a.playlist, a.timeout).decode("utf-8", "replace")
    else:
        text = open(a.playlist, encoding="utf-8", errors="replace").read()
    chans = parse_m3u(text)
    if a.filter:
        chans = [c for c in chans if a.filter.lower() in c[0].lower()]
    if a.limit:
        chans = chans[:a.limit]
    print("Каналов к проверке: %d" % len(chans), file=sys.stderr)

    if a.save_dir:
        os.makedirs(a.save_dir, exist_ok=True)
    counts = {"data": 0, "empty": 0, "none": 0, "error": 0}
    done = 0
    with ThreadPoolExecutor(a.workers) as ex:
        futs = [ex.submit(probe, n, u, a.timeout, a.segments, a.save_dir) for n, u in chans]
        for f in as_completed(futs):
            name, st, err, info = f.result()
            counts[st] += 1
            done += 1
            if done % 20 == 0:
                print("  проверено %d / %d" % (done, len(chans)), file=sys.stderr)
            if st == "data":
                print("ТЕЛЕТЕКСТ  %s: PID %d, пакетов %d, страницы в эфире: %s; объявлено: %s" % (
                    name, info["pid"], info["packets"],
                    ", ".join(map(str, info["pages"])) or "нет заголовков", info["declared"]), flush=True)
            elif a.all and st == "empty":
                print("пустой     %s: PID %d объявлен (%s), пакетов нет" % (name, info["pid"], info["declared"]), flush=True)
            elif a.all and st == "error":
                print("ошибка     %s: %s" % (name, err), flush=True)
    print("\nИтого: с данными %(data)d, объявлен, но пустой %(empty)d, без телетекста %(none)d, ошибок %(error)d" % counts)


if __name__ == "__main__":
    main()

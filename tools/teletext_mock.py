#!/usr/bin/env python3
"""Синтетический HLS с телетекстом для проверки на эмуляторе.

Берёт сегменты MPEG-TS, в PMT которых объявлена дорожка телетекста (даже пустая — так
бывает у «Первого»), и дописывает в неё пакеты: страницы из образца `tools/samples/*.ttx`
по кругу и свои субтитры на странице 888 (жёлтый текст в окне, затем пустая страница).
Рядом кладёт `index.m3u8` (VOD из этих сегментов). Дальше — `python3 -m http.server 8765
--bind 127.0.0.1` в папке и `adb reverse tcp:8765 tcp:8765`, ссылка канала —
`http://127.0.0.1:8765/index.m3u8` (README, «Проверка на эмуляторе»).

    tools/teletext_mock.py OUT_DIR seg0.ts seg1.ts ... [--sample tools/samples/НТВ_Мурманск.ttx]

Нужен ffprobe (время первого кадра сегмента). Ссылки провайдеров сюда не попадают — только файлы.
"""
import argparse
import os
import struct
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import find_teletext as ft  # noqa: E402

REV = ft.REV
LOWER = "юабцдефгхийклмнопярстужвьъзшэщч"   # 0x60…0x7E в наборе «русский/болгарский» («ы» — 0x26)
UPPER = "ЮАБЦДЕФГХИЙКЛМНОПЯРСТУЖВЬЪЗШЭЩЧ"   # 0x40…0x5E («Ы» — 0x5F)
PHRASES = [["Привет! Это субтитры телетекста"], ["Вторая строка субтитров", "и еще одна строка"], None,
           ["Проверка: Ж Ш Щ Ы Э Ю Я, цифры 12345"], None]


def enc84(n):
    """Хэмминг 8/4: P1 D1 P2 D2 P3 D3 P4 D4, нечётная чётность"""
    d1, d2, d3, d4 = n & 1, (n >> 1) & 1, (n >> 2) & 1, (n >> 3) & 1
    p1, p2, p3 = 1 ^ d1 ^ d3 ^ d4, 1 ^ d1 ^ d2 ^ d4, 1 ^ d1 ^ d2 ^ d3
    low = p1 | d1 << 1 | p2 << 2 | d2 << 3 | p3 << 4 | d3 << 5 | d4 << 7
    return low | ((bin(low).count("1") + 1) & 1) << 6


def parity(b):
    return b | (0x80 if bin(b).count("1") % 2 == 0 else 0)


def cyr(s):
    out = []
    for ch in s:
        if ch == "ы":
            out.append(0x26)
        elif ch == "Ы":
            out.append(0x5F)
        else:
            out.append(0x60 + LOWER.index(ch) if ch in LOWER else 0x40 + UPPER.index(ch) if ch in UPPER else ord(ch))
    return out


def unit(mag, row, payload):
    a = (mag & 7) | (row << 3)
    return bytes([0x02, 44, 0x00, 0xE4, REV[enc84(a & 15)], REV[enc84(a >> 4)]]) + bytes(REV[x] for x in payload)


def header(num, sub=False, chars=b""):
    p = [enc84(num & 15), enc84((num >> 4) & 15), enc84(0), enc84(8), enc84(0), enc84(8 if sub else 0), enc84(0), enc84(0)]
    p += [parity(c) for c in chars.ljust(32, b" ")]
    return unit((num >> 8) & 7, 0, bytes(p))


def row(num, r, data):
    return unit((num >> 8) & 7, r, bytes(parity(c) for c in bytes(data).ljust(40, b" ")))


def subtitle_page(lines):
    units = [header(0x888, True)]
    for i, line in enumerate(lines):
        raw = [0x0B, 0x0B] + cyr(line) + [0x0A, 0x0A]  # окно: начало … конец
        units.append(row(0x888, 21 + i, ([0x20] * ((40 - len(raw)) // 2) + [0x03] + raw)[:40]))
    units.append(header(0x8FF))  # заполнитель времени закрывает страницу
    return units


def pack(units, pts):
    """PES по 184 байта (ровно один TS-пакет): до трёх блоков по 46 байт, остаток — заполнитель"""
    out = []
    for i in range(0, len(units), 3):
        chunk = units[i:i + 3]
        while len(chunk) < 3:
            chunk.append(bytes([0xFF, 44]) + b"\xFF" * 44)
        payload = b"\x10" + b"".join(chunk)
        hdl = 184 - 9 - len(payload)
        pes = b"\x00\x00\x01\xbd" + struct.pack(">H", 184 - 6) + b"\x84\x80" + bytes([hdl]) + pts_bytes(pts) + b"\xff" * (hdl - 5) + payload
        out.append(pes)
    return out


def pts_bytes(t):
    return bytes([0x21 | ((t >> 29) & 0x0E), (t >> 22) & 0xFF, 0x01 | ((t >> 14) & 0xFE), (t >> 7) & 0xFF, 0x01 | ((t << 1) & 0xFE)])


def load_sample(path):
    d = open(path, "rb").read()
    i, out = 0, []
    while i < len(d):
        n = int.from_bytes(d[i:i + 4], "big")
        out.append(bytearray(d[i + 4:i + 4 + n]))
        i += 4 + n
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out_dir")
    ap.add_argument("segments", nargs="+")
    ap.add_argument("--sample", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "samples", "НТВ_Мурманск.ttx"))
    ap.add_argument("--per-segment", type=int, default=120, help="пакетов страниц на сегмент")
    a = ap.parse_args()
    os.makedirs(a.out_dir, exist_ok=True)
    sample = load_sample(a.sample)
    cc, si, durations = 0, 0, []
    for k, path in enumerate(a.segments):
        seg = open(path, "rb").read()
        es = ft.analyse(seg)[0]
        if not es:
            sys.exit("в %s нет дорожки телетекста в PMT" % path)
        pid = next(iter(es))
        out = subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time",
                                       "-read_intervals", "%+#1", "-of", "csv=p=0", path], stderr=subprocess.DEVNULL).decode().split()
        pts0 = int(float(out[0]) * 90000)
        dur = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                                            stderr=subprocess.DEVNULL).decode().split()[0])
        durations.append(dur)
        pk = [seg[i:i + 188] for i in range(0, len(seg), 188)]
        n = a.per_segment
        marks = (n // 6, n // 2, n * 2 // 3)  # где вставлять смену субтитров
        step = len(pk) // n
        events = []
        for j in range(n):
            t = pts0 + int(j * dur * 90000 / n)
            extra = []
            if j in marks:
                ph = PHRASES[(k * 3 + marks.index(j)) % len(PHRASES)]
                extra = pack(subtitle_page(ph) if ph else [header(0x888, True), header(0x8FF)], t)
            pe = sample[si % len(sample)]
            si += 1
            pe[9:14] = pts_bytes(t)
            events.append((j * step, [bytes(pe)] + extra))
        res, ei = [], 0
        for i, p in enumerate(pk):
            while ei < len(events) and events[ei][0] == i:
                for pes in events[ei][1]:
                    res.append(bytes([0x47, 0x40 | (pid >> 8), pid & 255, 0x10 | cc]) + pes)
                    cc = (cc + 1) & 15
                ei += 1
            res.append(p)
        with open(os.path.join(a.out_dir, "seg%d.ts" % k), "wb") as f:
            f.write(b"".join(res))
    with open(os.path.join(a.out_dir, "index.m3u8"), "w") as f:
        f.write("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:%d\n#EXT-X-MEDIA-SEQUENCE:0\n" % (int(max(durations)) + 1))
        for k, d in enumerate(durations):
            f.write("#EXTINF:%.3f,\nseg%d.ts\n" % (d, k))
        f.write("#EXT-X-ENDLIST\n")
    print("готово: %s (%d сегм.)" % (a.out_dir, len(durations)))


if __name__ == "__main__":
    main()

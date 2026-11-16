# Perfect TV — IPTV-плеер для Android TV

Нативное приложение для пульта: полноэкранный плеер, ТВ-гид в виде сетки и списка, программа и архив (catchup) по дням, инфопанель, избранное, скрытие, адаптивная сортировка. Пакет `top.rootu.perfecttv`.

- Плейлист (M3U/M3U8) и программа (XMLTV, в том числе .gz и .zip) загружаются **потоково** и сразу пишутся в SQLite. Каналы и передачи видны по ходу загрузки.
- Источник программы по умолчанию берётся из плейлиста (`url-tvg` / `x-tvg-url`, в том числе несколько адресов). В настройках плейлиста можно добавить свои источники и выбрать режим: «из плейлиста», «плейлист + свои» или «только свои».
- Видео — Media3 ExoPlayer (HLS, DASH, MPEG-TS, RTSP), с заголовками канала (`http-user-agent`, `http-referrer`, `#EXTHTTP`).

Документы:
- [docs/TZ.md](docs/TZ.md) — техническое задание со всеми нюансами;
- [docs/controls.md](docs/controls.md) — управление: клавиши, окна, PIN, напоминания, тач;
- [docs/design.md](docs/design.md) — оформление: масштаб, палитра, фокус, логотипы;
- [docs/PLAN.md](docs/PLAN.md) — план по фазам и что проверено;
- [docs/redesign.md](docs/redesign.md) — аудит интерфейса и план редизайна, макеты — `docs/mockups/redesign.html`.

## Лицензия и участие

GNU GPL 3.0 (`LICENSE`), сторонние компоненты — в `NOTICE`. Название и логотип «Perfect TV» лицензией не охвачены. Хотите помочь — `CONTRIBUTING.md` (в том числе соглашение `CLA.md`). Поддержать проект: Настройки → О программе → «Поддержать проект» (QR) или <https://boosty.to/rootu/donate>.

## Сборка

Нужны JDK 17 и Android SDK (путь — в `local.properties`, `sdk.dir=…`).

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

APK — `app/build/outputs/apk/debug/app-debug.apk`. Release (`assembleRelease`) пока подписывается debug-ключом.

## Проверка на эмуляторе

```bash
~/Android/Sdk/emulator/emulator -avd Android_TV_720p_API_30 -no-window -no-audio
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Пульт — `adb shell input keyevent KEYCODE_DPAD_UP` и т.п., снимок экрана — `adb exec-out screencap -p > s.png`. Текст в поле ввода — `adb shell input text '…'`.

Телетекст без настоящего эфира: `tools/find_teletext.py плейлист.m3u` показывает каналы, где данные телетекста идут (`--save-dir` сохраняет образцы для тестов в `tools/samples`); `tools/teletext_mock.py` вставляет пакеты из образца в сегменты TS с объявленной в PMT дорожкой и собирает `index.m3u8` — отдать `python3 -m http.server 8765 --bind 127.0.0.1` и `adb reverse tcp:8765 tcp:8765`, ссылка канала — `http://127.0.0.1:8765/index.m3u8` (подменить в БД приложения, как для остальных проверок).

Архив Flussonic без настоящего сервера — `tools/flussonic_mock.py` (нужен ffmpeg): эфир, `archive-START-DURATION[.m3u8?event=true]`, `…-now.m3u8`; в кадре — время сегмента, на шкале инфопанели должно быть то же. Второй канал — без PROGRAM-DATE-TIME. Поясом времени в кадре — как у эмулятора (`adb shell getprop persist.sys.timezone`):

```bash
python3 tools/flussonic_mock.py 8471 UTC
```

Плейлист в приложении — `http://10.0.2.2:8471/list.m3u`, в архив — перемотка назад (`KEYCODE_MEDIA_REWIND`).

## Устройство кода

```
app/src/main/java/top/rootu/perfecttv/
  App.kt                 инициализация: настройки, сеть, репозиторий
  core/                  Clock (время с поправкой по серверу), Net (OkHttp), Prefs (настройки)
  data/                  Db (схема SQLite), M3uParser, XmltvParser (байтовый), Streams (gzip/zip),
                         Loaders (потоковая запись в БД), EpgMatcher и Names (сопоставление), Catalog,
                         Repo (данные для интерфейса, кэш программы), Bus (события загрузки)
  player/                Playback (ExoPlayer), CatchupUrl (шаблоны архива)
  ui/                    TvActivity, Tv (оркестратор: состояние, слои, клавиши, архив), Keys (долгие нажатия, удержание),
                         Layer, InfoPanel, GuideLayer (гид на Canvas), ScheduleLayer (программа по дням),
                         SearchLayer (поиск со своей клавиатурой), MenuLayer (меню канала),
                         Popups (окно, список, карточка, тост), Widgets (карусель, сообщение, форма),
                         Cells (ячейки сетки), Logos (логотипы и заглушки), U (палитра, размеры, Fmt),
                         SettingsActivity
```

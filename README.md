# AmneziaGuard

Нативный Android VPN-клиент на протоколе **AmneziaWG 2.0** со встроенным пофайловым (per-app) фаерволом, kill-switch, защитой от DNS-утечек и плиткой в панели быстрых настроек (Quick Settings).

> Разработано по [техническому заданию](amneziaguard_tz.md). Ядро туннеля — официальная userspace-реализация AmneziaWG (`amneziawg-go`), подключённая как готовый `.aar` c Maven Central; сборка из Go/NDK не требуется.

## Возможности

- **AmneziaWG 2.0** — полный набор обфускационных параметров (`Jc/Jmin/Jmax`, `S1–S4`, `H1–H4`, `I1–I5`, сигнатурные пакеты), реконнект при смене сети без разрыва TCP-сессий, несколько сохранённых серверов.
- **Per-app фаервол, 3 режима** на каждое приложение: *через VPN*, *напрямую* (в обход туннеля), *без интернета*.
- **Kill-switch** — блокировка трафика при обрыве туннеля.
- **DNS-leak protection** — принудительный DNS через туннель + периодический self-test.
- **Quick Settings Tile** — подключение/отключение из шторки, long-press → выбор сервера.
- **Импорт конфигов** — вставка текста, файл `.conf`, QR-код.
- **Безопасность** — no-logs; приватные и preshared-ключи только в Android Keystore / EncryptedSharedPreferences.

## Стек

Kotlin · Jetpack Compose + Material 3 · `VpnService` · Hilt · Room · DataStore · WorkManager · Navigation Compose. **minSdk 26**, compile/target SDK 36, JDK 17.

Ядро туннеля: [`com.zaneschepke:amneziawg-android`](https://central.sonatype.com/artifact/com.zaneschepke/amneziawg-android) (форк официального `amnezia-vpn/amneziawg-android`).

## Модули

```
:app              — точка входа, навигация, тема
:core-tunnel      — обёртка над amneziawg-go, парсер конфигов AWG 2.0, TunnelManager
:core-firewall    — компилятор per-app политики, kill-switch, DNS-leak, root-путь
:core-data        — Room, DataStore, EncryptedSharedPreferences, репозитории
:core-ui          — тема, общие Compose-компоненты
:feature-connect  — главный экран подключения
:feature-firewall — управление приложениями/группами
:feature-settings — серверы, редактор AWG-параметров, импорт
:tile             — QuickSettingsTileService
:background       — foreground VpnService, оркестратор состояния, уведомление
```

## Сборка

```bash
./gradlew assembleDebug          # debug APK → app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # юнит-тесты (парсер AWG, компилятор политики, репозитории)
```

Требуется JDK 17 и Android SDK (platform 36, build-tools 36.0.0). Путь к SDK — в `local.properties` (`sdk.dir=...`).

CI (GitHub Actions, `.github/workflows/android.yml`) на каждый push собирает debug APK и публикует его как artifact.

## Ограничения режима «без интернета» (важно)

Штатный `VpnService` не даёт готового API «полностью отрезать приложение от сети». Реализовано три яруса:

1. **Root / Shizuku** — `iptables`/`nftables` с owner-match по UID: полный, надёжный блок на уровне ядра. Требует прав root; при их отсутствии — деградация.
2. **Без root, туннель выключен** — отдельный blackhole-`VpnService` заворачивает BLOCK-приложения в «чёрную дыру».
3. **Без root, туннель включён** — BLOCK-приложения принудительно уходят в туннель (реальный IP скрыт, но полного обрыва сети без root в этом состоянии добиться нельзя). UI честно помечает такие правила бейджем «полный блок требует root».

## Статус реализации

Все этапы ТЗ реализованы:

| Этап ТЗ | Что сделано |
|---|---|
| 1. MVP-туннель | `TunnelManager` + foreground `VpnService`, экран Connect с VPN-consent |
| 2. Простой фаервол | 2 режима (через VPN / напрямую) через include/exclude |
| 3. Quick Settings Tile | `AmneziaTileService`, состояния, toggle, long-press → выбор сервера |
| 4. Продвинутый фаервол | 3-й режим «без интернета»: root (iptables) / blackhole / деградация |
| 5. Kill-switch + DNS | fail-closed blackhole при обрыве + реконнект; DNS-инъекция + self-test |
| 6. Группы, полировка | пресеты групп, импорт QR/файл/текст, редактор AWG-параметров |

## Тестирование на устройстве

Юнит-тесты (парсер AWG 2.0, компилятор политики фаервола) прогоняются в CI. Инструментальные тесты `VpnService` и плитки — ручная матрица (этап 7 ТЗ), в первую очередь на Samsung One UI:

- подключение к реальному AWG-серверу (импорт `.conf`/QR → Connect);
- сохранение состояния плитки при смене сети (Wi-Fi ↔ LTE) и после перезагрузки;
- kill-switch: принудительный обрыв туннеля → трафик защищённых приложений блокируется до реконнекта;
- DNS-leak self-test: «Проверить на утечку» и периодическая проверка;
- режим «без интернета»: с root (полный блок) и без root (blackhole при отключённом туннеле).

## Лицензия

Apache-2.0 (по образцу upstream `amneziawg-android`).

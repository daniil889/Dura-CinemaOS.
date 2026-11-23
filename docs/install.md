# Установка и настройка

## Требования

- ТВ-приставка Android TV на Android 7.0+ (проверено на Xiaomi Mi Box, `MIBOX4`, Android 12).
- Включённые «Для разработчиков» и **отладка по сети** (Настройки → Настройки устройства →
  О системе → нажмите *Сборка* семь раз).
- Компьютер с `adb` (Android SDK platform-tools) в той же сети.

## Сборка

```sh
./build.sh                        # использует JDK 17 и Android build-tools 34
VERSION_CODE=62 VERSION_NAME=6.9 ./build.sh
```

Скрипт компилирует ресурсы (`aapt2`), Java (`javac`), dex (`d8`), выравнивает и подписывает.
Отладочные сборки используют локальный debug-ключ; официальные релизы подписываются
сопровождающими приватным ключом. Android устанавливает обновления только с той же подписью,
поэтому при переходе между релизной и отладочной сборкой нужно сначала удалить приложение.

> **Про эту сборку.** Русская сборка подписана локальным debug-ключом
> (`CN=tvOS Launcher debug`), который не совпадает с ключом официальных релизов. Поверх
> официальной сборки она не установится — сначала удалите оригинал:
> `adb uninstall com.techgenius.tvoslauncher`. Настройки при этом сбрасываются, поэтому
> сделайте резервную копию (Настройки → Система → Резервная копия) и восстановите её после
> установки.

## Установка и назначение домашним экраном

```sh
TV=192.168.1.13:5555
adb connect $TV
adb -s $TV install -r build/tvos-home.apk

# Android TV держит свой лаунчер впереди, пока он не отключён:
adb -s $TV shell pm disable-user --user 0 com.google.android.tvlauncher
adb -s $TV shell pm set-home-activity com.techgenius.tvoslauncher/.MainActivity
```

Нажмите «Домой» на пульте — откроется tvOS Launcher.

## Разовые разрешения

tvOS Launcher запрашивает их **на экране** (последняя страница настройки и Настройки →
Разрешения): это системные запросы Android, собственные экраны настроек системы или переключатель
специальных возможностей tvOS Launcher. Команды ниже — только крайняя мера для телевизоров, где
такого экрана нет.

| Возможность | Команда |
|---|---|
| «Продолжить просмотр» + «Не беспокоить» | `adb shell cmd notification allow_listener com.techgenius.tvoslauncher/com.techgenius.tvoslauncher.MediaWatchService` |
| Выключение + таймер сна | `adb shell dpm set-active-admin com.techgenius.tvoslauncher/.SleepReceiver\$Admin` |
| Имя Wi-Fi в панели управления | `adb shell pm grant com.techgenius.tvoslauncher android.permission.ACCESS_FINE_LOCATION` |
| Диктовка в поиске (микрофон) | `adb shell pm grant com.techgenius.tvoslauncher android.permission.RECORD_AUDIO` — обычно не нужно: без него поиск использует собственный голосовой запрос телевизора |

## Необязательно: источник рекомендаций

Рекомендации по умолчанию берутся из JustWatch (настройка не нужна). **Настройки → Верхняя
полка → Источник** также предлагает **Stremio** (популярные фильмы и сериалы Cinemeta, без
настройки; названия открываются в приложении Stremio или в Nuvio, если установлен только Nuvio)
и **TMDB**. Для TMDB введите бесплатный API-ключ с themoviedb.org (Настройки → API). Короткий
ключ v3 *API Key* проще всего ввести с пульта. Его также можно задать с компьютера:

```sh
adb shell am start -n com.techgenius.tvoslauncher/.SettingsActivity --es tmdb_token <token>
```

## Необязательно: приёмник AirPlay

Чтобы транслировать по AirPlay с Mac, iPhone или iPad на телевизор, установите open-source
приложение [AirPlay Server](https://github.com/jqssun/android-airplay-server) (GPL-3.0; это
отдельное приложение, не часть tvOS Launcher). Скачайте `app-release.apk` из
[последнего релиза](https://github.com/jqssun/android-airplay-server/releases/latest) и
установите:

```sh
adb install -r app-release.apk
```

Откройте его один раз из tvOS Launcher, задайте имя (например, «Mi Box») в его настройках и
оставьте включённым *Запуск при загрузке*. После этого телевизор появится в AirPlay на
устройствах Apple в той же сети Wi-Fi. Защита AirPlay в tvOS Launcher сохраняет его видимость
после засыпания телевизора ([возможности](features.md#airplay-с-приложением-airplay-server)).
Проверено на версии 0.0.36 с Mi Box (Android 12) и телевизором на Android 9.

## Откат

```sh
adb shell pm enable com.google.android.tvlauncher     # вернуть штатный лаунчер
adb uninstall com.techgenius.tvoslauncher
```

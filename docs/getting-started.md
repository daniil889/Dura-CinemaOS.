# Начало работы: сборка и запуск tvOS Launcher

От чистой машины до работающего на телевизоре (или в эмуляторе Android TV) tvOS Launcher примерно
за 20 минут. Android Studio и Gradle не нужны: проект собирается штатными консольными
инструментами Android SDK.

## 1. Что нужно

| | Версия | Зачем |
|---|---|---|
| JDK | 17 или новее | `javac` компилирует исходники Java (`--release 11`) |
| Android SDK build-tools | **34.0.0** | `aapt2`, `d8`, `zipalign`, `apksigner` |
| Android SDK platform | **android-34** | `android.jar` для компиляции |
| Android SDK platform-tools | последняя | `adb` для установки и отладки |
| Устройство | Android 6.0+ (API 23+) | ТВ-приставка/телевизор или эмулятор Android TV |

Проверенные устройства: Xiaomi Mi Box 4 (Android 12, 32-битный ARM, GPU Mali-450) и телевизор
SPPL на Android 9 (1 ГБ ОЗУ). Цель — слабое железо, поэтому по возможности тестируйте именно на
нём.

## 2. Установка инструментов

### macOS

```sh
brew install --cask temurin@17                      # JDK 17 (или любой JDK 17+)
brew install --cask android-commandlinetools        # sdkmanager
export ANDROID_HOME="$HOME/Library/Android/sdk"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "build-tools;34.0.0" "platforms;android-34"
```

### Linux (Debian/Ubuntu)

```sh
sudo apt install openjdk-17-jdk unzip
mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
curl -O https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip && mv cmdline-tools latest
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
yes | sdkmanager --licenses
sdkmanager "platform-tools" "build-tools;34.0.0" "platforms;android-34"
```

### Windows

Вариант 1 — **WSL 2** (Ubuntu) и шаги для Linux: `build.sh` — это bash-скрипт. Чтобы установить
приложение на телевизор по сети из WSL, телевизор и ПК должны быть в одной локальной сети.

Вариант 2 — **PowerShell**: в репозитории есть `build.ps1`, который делает то же самое без WSL.

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
# APK -> build\tvos-home.apk
```

Пропишите `ANDROID_HOME` (и `JAVA_HOME`, если `javac` нет в `PATH`) в профиль оболочки.
`build.sh` читает их и при отсутствии использует `~/Library/Android/sdk` (macOS) или
`~/Android/Sdk` (Linux).

## 3. Получение кода и сборка

```sh
git clone https://github.com/disasdter/tvOS-Launcher-RUS-.git   # этот форк
cd tvOS-Launcher-RUS-
./build.sh                                                     # → build/tvos-home.apk
VERSION_CODE=88 VERSION_NAME=7.5 ./build.sh                     # релизные сборки задают версию
```

Что делает `build.sh` по порядку: `aapt2` компилирует ресурсы и ассеты → `javac` компилирует
`src/` → `d8` переводит в DEX → `zipalign` → `apksigner` подписывает локальным debug-ключом
(`debug.keystore`, создаётся при первой сборке и никогда не попадает в git). Полная сборка
занимает около 15 секунд. Если какого-то инструмента нет, скрипт скажет какого и как его получить.

## 4. Запуск на телевизоре

1. На телевизоре: **Настройки → Настройки устройства → О системе → Сборка**, нажмите 7 раз,
   чтобы открыть «Для разработчиков»; затем **Для разработчиков → Отладка по USB / Отладка по
   сети** — включить.
2. С компьютера (в той же сети):

```sh
adb connect <ip-тв>:5555          # подтвердите запрос «Разрешить отладку?» на телевизоре
adb install -r build/tvos-home.apk
adb shell am start -n com.techgenius.tvoslauncher/.MainActivity
```

3. При первом запуске открываются страницы настройки. Чтобы сделать tvOS Launcher домашним
   экраном, завершите настройку (там это предлагается) или, на устройствах Google TV, где
   штатный лаунчер всегда побеждает:
   `adb shell pm disable-user --user 0 com.google.android.tvlauncher` (откат — `pm enable`).

Подробнее об установке и разрешениях: [install.md](install.md).

## 5. Или эмулятор Android TV

```sh
sdkmanager --list | grep "android-tv"          # выберите образ Android TV под свой CPU (x86_64 или arm64-v8a)
sdkmanager "emulator" "system-images;android-<api>;android-tv;<abi>"
avdmanager create avd -n tv -k "system-images;android-<api>;android-tv;<abi>" -d tv_1080p
emulator -avd tv &
adb install -r build/tvos-home.apk
```

Эмулятор подходит для проверки раскладки и логики. Он **не** показывает реальную
производительность (его GPU намного быстрее, чем у ТВ-приставки), и в нём нет HDMI-входов,
поэтому их нужно проверять на железе.

## 6. Первое изменение

1. Возьмите что-нибудь небольшое, например подпись в `SettingsActivity.java`.
2. Соберите и установите: `./build.sh && adb install -r build/tvos-home.apk`.
3. Перейдите сразу на изменённый экран ярлыками разработчика ниже и сделайте скриншот:
   `adb exec-out screencap -p > shot.png`.
4. Прочитайте **[CONTRIBUTING.md](../CONTRIBUTING.md)** перед созданием pull request.

### Ярлыки разработчика (adb)

Открыть любой экран, не проходя интерфейс вручную:

| Команда | Что открывает |
|---|---|
| `adb shell am start -n com.techgenius.tvoslauncher/.MainActivity --es panel cc` | Панель управления (также работают `inputs`, `weather`, `present`) |
| `adb shell am start -n com.techgenius.tvoslauncher/.MainActivity --es panel search --es q panchayat` | Поиск с уже введённым запросом |
| `adb shell am start -n com.techgenius.tvoslauncher/.MainActivity --ez pick_profile true` | Переключатель профилей |
| `adb shell am start -n com.techgenius.tvoslauncher/.SettingsActivity --es page "Top Shelf"` | Страница настроек по её названию |
| `adb shell am start -n com.techgenius.tvoslauncher/.SetupActivity --ei step 3` | Страница настройки 0–7 |
| `adb shell am start -n com.techgenius.tvoslauncher/.SetupActivity --ez skip true` | Пометить настройку завершённой |
| `adb shell am start -n com.techgenius.tvoslauncher/.SettingsActivity --es tmdb_token <key>` | Задать ключ TMDB |
| `adb shell am start -n com.techgenius.tvoslauncher/.MainActivity --es perf weather` | Скрыть один слой, чтобы измерить его стоимость для GPU (`status`, `hero`, `dockglass`, `shadows`; `reset` возвращает всё) |

Полезные логи: `adb logcat -s TvHome` (собственные сообщения tvOS Launcher) и
`adb shell dumpsys gfxinfo com.techgenius.tvoslauncher` (время кадров; см. CONTRIBUTING.md).

> В русской сборке названия страниц настроек тоже русские, поэтому `--es page "Top Shelf"`
> работает как в оригинале, а `--es page "Верхняя полка"` — в локализованном интерфейсе.

## Решение проблем

| Проблема | Решение |
|---|---|
| `Missing …/aapt2` | Установите `build-tools;34.0.0` и `platforms;android-34` или задайте `ANDROID_HOME` |
| `javac not found` | Установите JDK 17+ или задайте `JAVA_HOME` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Установленная копия подписана другим ключом (например, релизная сборка): сначала `adb uninstall com.techgenius.tvoslauncher` (это очистит её настройки) |
| `adb: device offline` / `unauthorized` | Подтвердите запрос на телевизоре; выполните `adb disconnect`, затем снова `adb connect` |
| Кнопка «Домой» всё ещё открывает старый лаунчер | Завершите шаг настройки «Сделать tvOS Launcher домашним экраном» или см. install.md |
| Установка по Wi-Fi очень медленная | Это нормально для 1 МБ+ в загруженной сети; она завершится — не оборачивайте её в фиксированные задержки |

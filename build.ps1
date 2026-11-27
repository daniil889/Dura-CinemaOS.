$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME) {
    $jdkCandidates = @(
        "C:\Program Files\Java\jdk-17",
        "C:\Program Files\Java\jdk-21",
        "C:\Program Files\Eclipse Adoptium\jdk-17*",
        "C:\Program Files\Microsoft\jdk-17*"
    )
    foreach ($cand in $jdkCandidates) {
        $found = Get-Item $cand -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found -and (Test-Path "$($found.FullName)\bin\javac.exe")) {
            $env:JAVA_HOME = $found.FullName
            break
        }
    }
}
if ($env:JAVA_HOME) {
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

$SDK = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$BT = "$SDK\build-tools\34.0.0"
$JAR = "$SDK\platforms\android-34\android.jar"
# d8 34.0.0 crashes on these classes with JDK 21; 35/36 handle them fine.
$D8JAR = "$SDK\build-tools\36.0.0\lib\d8.jar"
if (-not (Test-Path $D8JAR)) { $D8JAR = "$SDK\build-tools\35.0.0\lib\d8.jar" }
if (-not (Test-Path $D8JAR)) { $D8JAR = "$BT\lib\d8.jar" }

$requiredTools = @("$BT\aapt2.exe", "$BT\zipalign.exe", "$BT\apksigner.bat", "$JAR", "$D8JAR")
foreach ($tool in $requiredTools) {
    if (-not (Test-Path $tool)) { throw "Missing required Android SDK tool: $tool. Install build-tools 34.0.0 and 35.0.0/36.0.0, plus platform android-34, or set ANDROID_HOME." }
}
foreach ($tool in @("javac", "jar", "keytool", "java")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool not found. Install JDK 17+ and set JAVA_HOME." }
}

$versionCode = if ($env:VERSION_CODE) { $env:VERSION_CODE } else { "17" }
$versionName = if ($env:VERSION_NAME) { $env:VERSION_NAME } else { "2.2.5" }

if (Test-Path "build") { Get-ChildItem -Path "build" | Remove-Item -Recurse -Force }
New-Item -ItemType Directory -Force -Path "build\gen", "build\classes", "build\dex" | Out-Null

& "$BT\aapt2.exe" compile --dir res -o build\res.zip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

& "$BT\aapt2.exe" link -o build\unsigned.apk -I "$JAR" --manifest AndroidManifest.xml `
    --java build\gen --min-sdk-version 23 --target-sdk-version 34 `
    --version-code $versionCode --version-name $versionName build\res.zip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$javaFiles = Get-ChildItem -Recurse -Path src, build\gen -Filter "*.java" | Select-Object -ExpandProperty FullName
$javaFiles | Out-File -Encoding ascii build\sources.txt
& javac -encoding UTF-8 --release 11 -classpath "$JAR" -d build\classes "@build\sources.txt"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

& jar cf build\classes.jar -C build\classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

& java -cp "$D8JAR" com.android.tools.r8.D8 --release --min-api 23 --lib "$JAR" --output build\dex build\classes.jar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open((Resolve-Path "build\unsigned.apk").Path, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Resolve-Path "build\dex\classes.dex").Path, "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null

    # Package assets with POSIX forward slashes (Windows aapt2 -A writes backslashes which Android AssetManager cannot open)
    $assetsRoot = (Resolve-Path "assets").Path
    $noCompressExts = @(".mp3", ".wav", ".ogg", ".mp4", ".webm")
    Get-ChildItem -Recurse -File -Path $assetsRoot | ForEach-Object {
        $rel = $_.FullName.Substring($assetsRoot.Length + 1).Replace('\', '/')
        $entryName = "assets/$rel"
        $existing = $archive.GetEntry($entryName)
        if ($existing) { $existing.Delete() }
        $ext = $_.Extension.ToLowerInvariant()
        $level = if ($noCompressExts -contains $ext) {
            [System.IO.Compression.CompressionLevel]::NoCompression
        } else {
            [System.IO.Compression.CompressionLevel]::Optimal
        }
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $_.FullName, $entryName, $level) | Out-Null
    }
} finally {
    $archive.Dispose()
}

& "$BT\zipalign.exe" -f 4 build\unsigned.apk build\aligned.apk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$KS = "debug.keystore"
if (-not $env:RELEASE_KEYSTORE -and -not (Test-Path $KS)) {
    & keytool -genkeypair -keystore $KS -storepass android -keypass android -alias debug `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvOS Launcher debug" -noprompt | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Debug keystore generation failed" }
}

if ($env:RELEASE_KEYSTORE) {
    if (-not $env:RELEASE_KEYSTORE_PASS) { throw "Set RELEASE_KEYSTORE_PASS when RELEASE_KEYSTORE is configured." }
    $alias = if ($env:RELEASE_KEY_ALIAS) { $env:RELEASE_KEY_ALIAS } else { "tvos-release" }
    & "$BT\apksigner.bat" sign --ks $env:RELEASE_KEYSTORE --ks-pass "env:RELEASE_KEYSTORE_PASS" --ks-key-alias $alias --out build\tvos-home.apk build\aligned.apk
    if ($LASTEXITCODE -ne 0) { throw "Release APK signing failed" }
    Write-Host "Signed with the release key"
} else {
    & "$BT\apksigner.bat" sign --ks $KS --ks-pass pass:android --key-pass pass:android --out build\tvos-home.apk build\aligned.apk
    if ($LASTEXITCODE -ne 0) { throw "Debug APK signing failed" }
    Write-Host "Signed with the local debug key"
}

& "$BT\apksigner.bat" verify --verbose build\tvos-home.apk
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed" }

& "$BT\zipalign.exe" -c -v 4 build\tvos-home.apk
if ($LASTEXITCODE -ne 0) { throw "APK alignment verification failed" }

Write-Host "Built build\tvos-home.apk"

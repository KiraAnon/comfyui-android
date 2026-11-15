# 手工编译 ComfyUI 安卓壳 APK（不依赖 Gradle，只用 build-tools）
# 用法： powershell -ExecutionPolicy Bypass -File app\build.ps1 [-Install]
#
# 签名密钥：仓库里不带 .jks。首次构建会自动生成一个，密码从环境变量读：
#   $env:KATORCH_KS_PASS = '你的密码'
# 没设的话会用一个随机密码并打印出来，记得存好（换密码就不能覆盖升级了）。
param([switch]$Install)

$ErrorActionPreference = 'Continue'
$ROOT = $PSScriptRoot

# ---- 签名密码（不硬编码） ----
$KS_PASS = $env:KATORCH_KS_PASS
$KS_PASS_FILE = Join-Path $PSScriptRoot '.kspass'
if ([string]::IsNullOrWhiteSpace($KS_PASS)) {
    if (Test-Path $KS_PASS_FILE) {
        # 密码持久化在 app\.kspass（已 gitignore）。以前只打印到屏幕，
        # 第二次编译就换了密码、签不了名，只能卸载重装，坑过一次。
        $KS_PASS = (Get-Content $KS_PASS_FILE -Raw).Trim()
    }
    if ([string]::IsNullOrWhiteSpace($KS_PASS)) {
        $KS_PASS = -join ((48..57) + (97..122) | Get-Random -Count 20 | ForEach-Object { [char]$_ })
        [System.IO.File]::WriteAllText($KS_PASS_FILE, $KS_PASS, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host ''
        Write-Host "!! 已生成签名密码并保存到 $KS_PASS_FILE" -ForegroundColor Yellow
        Write-Host "   $KS_PASS" -ForegroundColor Yellow
        Write-Host '   这个文件别删，删了就签不了名、只能卸载重装。' -ForegroundColor Yellow
        Write-Host ''
    }
}
$KS_ALIAS = if ($env:KATORCH_KS_ALIAS) { $env:KATORCH_KS_ALIAS } else { 'comfyui' }

# ---- 自动探测 Android SDK / JDK ----
# 优先级：环境变量 > 常见安装位置 > PATH

function Find-Sdk {
    # 1. 环境变量
    foreach ($v in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, $env:ANDROID_SDK)) {
        if ($v -and (Test-Path (Join-Path $v 'platform-tools'))) { return $v }
    }
    # 2. 常见安装位置
    $cands = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
        (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk'),
        'C:\Android\Sdk',
        'D:\Android\Sdk',
        'D:\AndroidSdk',
        '/usr/lib/android-sdk'
    )
    foreach ($c in $cands) {
        if ($c -and (Test-Path $c) -and (Test-Path (Join-Path $c 'platforms'))) { return $c }
    }
    return $null
}

function Find-Jdk {
    # 1. 环境变量（最可靠）
    foreach ($v in @($env:JAVA_HOME, $env:JDK_HOME)) {
        if ($v -and (Test-Path (Join-Path $v 'bin\javac.exe'))) { return $v }
    }

    # 2. 注册表里的 JDK 安装记录（Oracle/OpenJDK 都会写）
    $regRoots = @(
        'HKLM:\SOFTWARE\JavaSoft\JDK',
        'HKLM:\SOFTWARE\JavaSoft\Java Development Kit',
        'HKLM:\SOFTWARE\Eclipse Adoptium\JDK',
        'HKLM:\SOFTWARE\Microsoft\JDK'
    )
    foreach ($r in $regRoots) {
        if (-not (Test-Path $r)) { continue }
        $vers = Get-ChildItem $r -ErrorAction SilentlyContinue | Sort-Object Name -Descending
        foreach ($v in $vers) {
            $hp = (Get-ItemProperty $v.PSPath -ErrorAction SilentlyContinue).JavaHome
            if ($hp -and (Test-Path (Join-Path $hp 'bin\javac.exe'))) { return $hp }
        }
    }

    # 3. 直接扫常见 JDK 目录（不要走 PATH —— 那可能指向 Oracle 的 javapath shim 目录）
    foreach ($parent in @(
        'C:\Program Files\Java',
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Microsoft',
        'C:\Program Files\Android\Android Studio'
    )) {
        if (-not (Test-Path $parent)) { continue }
        foreach ($pat in @('jdk*', 'jdk-*', 'jbr')) {
            $d = Get-ChildItem $parent -Directory -Filter $pat -ErrorAction SilentlyContinue |
                 Sort-Object Name -Descending | Select-Object -First 1
            if ($d -and (Test-Path (Join-Path $d.FullName 'bin\javac.exe'))) { return $d.FullName }
            # Android Studio 的 jbr 在子目录
            if ($d) {
                $jbr = Join-Path $d.FullName 'jbr'
                if (Test-Path (Join-Path $jbr 'bin\javac.exe')) { return $jbr }
            }
        }
    }

    # 4. 最后才从 PATH 找，且要验证上两级真有 bin\javac.exe
    #    （PATH 上常见的是 C:\Program Files\Common Files\Oracle\Java\javapath 这类 shim 目录，不是真 JDK）
    $jc = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($jc) {
        $real = $null
        try { $real = (Get-Item $jc.Source).Target } catch { }
        if ($real) { $jcPath = $real } else { $jcPath = $jc.Source }
        for ($i = 0; $i -lt 4; $i++) {
            $jcPath = Split-Path $jcPath -Parent
            if (-not $jcPath) { break }
            if (Test-Path (Join-Path $jcPath 'bin\javac.exe')) {
                # 排除 shim 目录（有的 JDK 根目录下也有 bin，要确认是 JDK 不是 JRE）
                if ((Test-Path (Join-Path $jcPath 'bin\jar.exe')) -and
                    (Test-Path (Join-Path $jcPath 'bin\keytool.exe'))) {
                    return $jcPath
                }
            }
        }
    }

    return $null
}

function Find-LatestDir($parent, $pattern) {
    if (-not (Test-Path $parent)) { return $null }
    $d = Get-ChildItem $parent -Directory -Filter $pattern -ErrorAction SilentlyContinue |
         Sort-Object { [version]($_.Name -replace '[^\d.]', '') } -Descending -ErrorAction SilentlyContinue |
         Select-Object -First 1
    if ($d) { return $d.FullName }
    # 版本解析失败时退回名字排序
    $d = Get-ChildItem $parent -Directory -Filter $pattern -ErrorAction SilentlyContinue |
         Sort-Object Name -Descending | Select-Object -First 1
    if ($d) { return $d.FullName }
    return $null
}

$SDK = Find-Sdk
if (-not $SDK) {
    throw @'
找不到 Android SDK。任选一种方式：
  1. 设环境变量： $env:ANDROID_SDK_ROOT = '你的 SDK 路径'
  2. 或用 Android Studio 的 SDK Manager 装到默认位置
  3. 或装 commandline-tools 后跑： sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
'@
}

$BT = Find-LatestDir (Join-Path $SDK 'build-tools') '*'
if (-not $BT) { throw "SDK 里没有 build-tools：$SDK\build-tools（需要 sdkmanager 装）" }

$PLAT = Find-LatestDir (Join-Path $SDK 'platforms') 'android-*'
if (-not $PLAT) { throw "SDK 里没有 platforms：$SDK\platforms（需要 sdkmanager 装 android-35）" }
$ANDROID_JAR = Join-Path $PLAT 'android.jar'
$API_LEVEL = (Split-Path $PLAT -Leaf) -replace 'android-', ''

$JAVA_HOME = Find-Jdk
if (-not $JAVA_HOME) {
    throw @'
找不到 JDK（需要 17+）。任选一种方式：
  1. 设环境变量： $env:JAVA_HOME = '你的 JDK 路径'
  2. 或装一个： winget install Microsoft.OpenJDK.21
  3. 或装 Android Studio（自带 JBR）
'@
}

Write-Host ("   SDK      : {0}" -f $SDK)
Write-Host ("   build-tools: {0}" -f (Split-Path $BT -Leaf))
Write-Host ("   platform : {0} (API {1})" -f (Split-Path $PLAT -Leaf), $API_LEVEL)
Write-Host ("   JDK      : {0}" -f $JAVA_HOME)
Write-Host ''

$AAPT2 = Join-Path $BT 'aapt2.exe'
$D8 = Join-Path $BT 'd8.bat'
$ZIPALIGN = Join-Path $BT 'zipalign.exe'
$APKSIGNER = Join-Path $BT 'apksigner.bat'
$JAVAC = Join-Path $JAVA_HOME 'bin\javac.exe'
$JAR = Join-Path $JAVA_HOME 'bin\jar.exe'
$KEYTOOL = Join-Path $JAVA_HOME 'bin\keytool.exe'

$missing = @()
foreach ($p in @($AAPT2, $D8, $ZIPALIGN, $APKSIGNER, $ANDROID_JAR, $JAVAC)) {
    if (-not (Test-Path $p)) { $missing += $p }
}
if ($missing.Count -gt 0) {
    throw ("缺少构建工具：`n  " + ($missing -join "`n  ") + "`n`n跑一下： sdkmanager ""build-tools;35.0.0"" ""platforms;android-35""")
}

$BUILD = Join-Path $ROOT 'build'
$GEN = Join-Path $BUILD 'gen'
$CLS = Join-Path $BUILD 'classes'
$DEX = Join-Path $BUILD 'dex'
Remove-Item -Recurse -Force $BUILD -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $GEN, $CLS, $DEX | Out-Null

Write-Host '== 1/6 编译资源 (aapt2 compile)'
& $AAPT2 compile --dir (Join-Path $ROOT 'res') -o (Join-Path $BUILD 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

Write-Host '== 2/6 链接资源 + 生成 R.java (aapt2 link)'
$unsigned = Join-Path $BUILD 'unsigned.apk'
& $AAPT2 link -o $unsigned -I $ANDROID_JAR `
    --manifest (Join-Path $ROOT 'AndroidManifest.xml') `
    --java $GEN `
    --min-sdk-version 26 --target-sdk-version 35 `
    --version-code 1 --version-name 1.0 `
    (Join-Path $BUILD 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

Write-Host '== 3/6 编译 Java (javac)'
# 走 cmd 重定向：PowerShell 把 javac 的 stderr 警告包成 ErrorRecord，会让 $LASTEXITCODE 不可信
$sources = @(
    (Join-Path $GEN 'com\katorch\npu\R.java'),
    (Join-Path $ROOT 'java\com\katorch\npu\MainActivity.java'),
    (Join-Path $ROOT 'java\com\katorch\npu\StopService.java')
)
$jl = Join-Path $BUILD 'javac.log'
$srcArgs = ($sources | ForEach-Object { '"' + $_ + '"' }) -join ' '
cmd /c ('"' + $JAVAC + '" -encoding UTF-8 -nowarn -source 8 -target 8 -classpath "' + $ANDROID_JAR + '" -d "' + $CLS + '" ' + $srcArgs + ' > "' + $jl + '" 2>&1')
if ($LASTEXITCODE -ne 0) { throw ("javac 失败:`n" + (Get-Content $jl -Raw)) }

Write-Host '== 4/6 转 dex (d8)'
$classFiles = Get-ChildItem -Path $CLS -Recurse -Filter *.class | ForEach-Object { $_.FullName }
$dl = Join-Path $BUILD 'd8.log'
cmd /c ('"' + $D8 + '" --lib "' + $ANDROID_JAR + '" --min-api 26 --output "' + $DEX + '" ' +
        (($classFiles | ForEach-Object { '"' + $_ + '"' }) -join ' ') + ' > "' + $dl + '" 2>&1')
if ($LASTEXITCODE -ne 0) { throw ("d8 失败:`n" + (Get-Content $dl -Raw)) }

Write-Host '== 5/6 打包 dex 并 zipalign'
# classes.dex 必须放在 APK 根目录，否则安装时报 INSTALL_FAILED_INVALID_APK: code is missing
Copy-Item $unsigned (Join-Path $BUILD 'packed.apk') -Force
Copy-Item (Join-Path $DEX 'classes.dex') (Join-Path $BUILD 'classes.dex') -Force
Push-Location $BUILD
& $JAR uf 'packed.apk' 'classes.dex'
Pop-Location
if ($LASTEXITCODE -ne 0) { throw 'jar 打包 dex 失败' }
$aligned = Join-Path $BUILD 'aligned.apk'
& $ZIPALIGN -p -f 4 (Join-Path $BUILD 'packed.apk') $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

Write-Host '== 6/6 签名 (apksigner)'
$ks = Join-Path $ROOT 'comfyui.jks'
if (-not (Test-Path $ks)) {
    Write-Host '   没有 keystore，正在生成…'
    & $KEYTOOL -genkeypair -keystore $ks -alias $KS_ALIAS -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass $KS_PASS -keypass $KS_PASS `
        -dname 'CN=ComfyUI-Android, OU=Self, O=Personal, L=Local, ST=Local, C=CN' 2>&1 | Out-Null
    if (-not (Test-Path $ks)) { throw 'keystore 生成失败' }
    Write-Host "   已生成：$ks"
}
$apk = Join-Path $ROOT 'ComfyUI.apk'
& $APKSIGNER sign --ks $ks --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" `
    --min-sdk-version 26 --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败（密码不对？设对 $env:KATORCH_KS_PASS 再试）' }

& $APKSIGNER verify --min-sdk-version 26 $apk | Out-Null
Write-Host ("   产物：{0}  ({1:N0} KB)" -f $apk, ((Get-Item $apk).Length / 1KB))

if ($Install) {
    Write-Host '== 安装到手机'

    # adb 查找：环境变量 > SDK 里的 platform-tools > PATH
    $ADB = $null
    if ($env:ADB) { $ADB = $env:ADB }
    if (-not $ADB) {
        $cand = Join-Path $SDK 'platform-tools\adb.exe'
        if (Test-Path $cand) { $ADB = $cand }
    }
    if (-not $ADB) {
        $cmd = Get-Command adb -ErrorAction SilentlyContinue
        if ($cmd) { $ADB = $cmd.Source }
    }
    if (-not $ADB) {
        throw "找不到 adb。设 `$env:ADB = 'adb 路径'，或用 sdkmanager 装 platform-tools"
    }

    # 先确认有设备
    $devs = & $ADB devices 2>&1 | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
    if (-not $devs) { throw '没有连接的 Android 设备（先开 USB 调试并授权）' }

    & $ADB install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'adb install 失败' }
    Write-Host '   已安装'
}
Write-Host 'OK'

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
if ([string]::IsNullOrWhiteSpace($KS_PASS)) {
    $KS_PASS = -join ((48..57) + (97..122) | Get-Random -Count 20 | ForEach-Object { [char]$_ })
    Write-Host ''
    Write-Host '!! 没设 $env:KATORCH_KS_PASS，已随机生成一个：' -ForegroundColor Yellow
    Write-Host "   $KS_PASS" -ForegroundColor Yellow
    Write-Host '   请保存好！换了密码就没法覆盖升级已装的 app。' -ForegroundColor Yellow
    Write-Host ''
}
$KS_ALIAS = if ($env:KATORCH_KS_ALIAS) { $env:KATORCH_KS_ALIAS } else { 'comfyui' }

# ---- Android SDK / JDK（可用环境变量覆盖） ----
$SDK = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       else { 'D:\AndroidSdk' }
$BT = Join-Path $SDK 'build-tools\35.0.0'
$ANDROID_JAR = Join-Path $SDK 'platforms\android-35\android.jar'
$JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-21.0.10' }

$AAPT2 = Join-Path $BT 'aapt2.exe'
$D8 = Join-Path $BT 'd8.bat'
$ZIPALIGN = Join-Path $BT 'zipalign.exe'
$APKSIGNER = Join-Path $BT 'apksigner.bat'
$JAVAC = Join-Path $JAVA_HOME 'bin\javac.exe'
$JAR = Join-Path $JAVA_HOME 'bin\jar.exe'
$KEYTOOL = Join-Path $JAVA_HOME 'bin\keytool.exe'

foreach ($p in @($AAPT2, $D8, $ZIPALIGN, $APKSIGNER, $ANDROID_JAR, $JAVAC)) {
    if (-not (Test-Path $p)) { throw "缺少构建工具：$p（先跑 sdkmanager 装 platforms;android-35 + build-tools;35.0.0）" }
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
    $ADB = 'D:\comfyui\platform-tools\adb.exe'
    & $ADB install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'adb install 失败' }
    Write-Host '   已安装'
}
Write-Host 'OK'

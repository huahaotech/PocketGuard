param(
 [string]$Sdk = $env:ANDROID_HOME,
 [string]$Jdk = $env:JAVA_HOME,
 [string]$BuildToolsVersion = '35.0.0',
 [string]$Platform = 'android-35'
)
$ErrorActionPreference = 'Stop'
if (!$Sdk -or !$Jdk) { throw '请设置 ANDROID_HOME 和 JAVA_HOME，或通过 -Sdk / -Jdk 指定。' }
$root = $PSScriptRoot
$output = Join-Path $root 'build'
$buildTools = Join-Path $Sdk "build-tools/$BuildToolsVersion"
$androidJar = Join-Path $Sdk "platforms/$Platform/android.jar"
foreach($path in @("$Jdk/bin/javac.exe", "$buildTools/aapt2.exe", $androidJar)) {
 if (!(Test-Path -LiteralPath $path)) { throw "工具不存在：$path" }
}
function Assert-Success { if ($LASTEXITCODE -ne 0) { throw "构建失败：$LASTEXITCODE" } }
New-Item -ItemType Directory -Path "$output/gen", "$output/classes", "$output/dex" -Force | Out-Null
Push-Location $root
try {
 & "$buildTools/aapt2.exe" compile --dir "$root/res" -o "$output/resources.zip"
 Assert-Success
 & "$buildTools/aapt2.exe" link -o "$output/base.apk" -I $androidJar --manifest "$root/AndroidManifest.xml" -R "$output/resources.zip" --java "$output/gen" --min-sdk-version 26 --target-sdk-version 34 --version-code 3 --version-name 1.2 --auto-add-overlay
 Assert-Success
 $sources = @(Get-ChildItem "$root/src", "$output/gen" -Recurse -Filter *.java | Select-Object -ExpandProperty FullName)
 & "$Jdk/bin/javac.exe" -encoding UTF-8 -source 11 -target 11 -classpath $androidJar -d "$output/classes" @sources
 Assert-Success
 $classes = @(Get-ChildItem "$output/classes" -Recurse -Filter *.class | Select-Object -ExpandProperty FullName)
 & "$Jdk/bin/java.exe" -cp "$buildTools/lib/d8.jar" com.android.tools.r8.D8 --lib $androidJar --min-api 26 --output "$output/dex" @classes
 Assert-Success
 Copy-Item "$output/base.apk" "$output/unsigned.apk" -Force
 & "$Jdk/bin/jar.exe" uf "$output/unsigned.apk" -C "$output/dex" classes.dex
 Assert-Success
 & "$buildTools/zipalign.exe" -f -p 4 "$output/unsigned.apk" "$output/aligned.apk"
 Assert-Success
 $key = Join-Path $output 'debug.keystore'
 if (!(Test-Path -LiteralPath $key)) {
  & "$Jdk/bin/keytool.exe" -genkeypair -keystore $key -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10950 -dname 'CN=PocketGuard Debug'
  Assert-Success
 }
 & "$Jdk/bin/java.exe" -jar "$buildTools/lib/apksigner.jar" sign --ks $key --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out "$output/PocketGuard.apk" "$output/aligned.apk"
 Assert-Success
 & "$Jdk/bin/java.exe" -jar "$buildTools/lib/apksigner.jar" verify --verbose "$output/PocketGuard.apk"
 Assert-Success
 Write-Output "构建完成：$output/PocketGuard.apk"
} finally { Pop-Location }

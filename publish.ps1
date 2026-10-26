$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$gradle = Get-Content -LiteralPath "$root\app\build.gradle.kts" -Raw
if ($gradle -notmatch 'val releaseVersion = "([^"]+)"') {
    throw "В app\build.gradle.kts не найдена releaseVersion"
}
$version = $Matches[1]
$apk = Get-ChildItem -LiteralPath "$root\app\build\outputs\apk\debug" -Filter "*.apk" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $apk) {
    throw "APK ещё нет. Сначала соберите: .\gradlew.bat assembleDebug"
}

$tag = "v$version"
$asset = Join-Path $env:TEMP "group-calendar-$version.apk"
Copy-Item -LiteralPath $apk.FullName -Destination $asset -Force
$notes = "Версия $version. Ставится поверх прошлой. Если телефон отказывается обновлять, поднимите releaseCode в app\build.gradle.kts."
gh release view $tag *> $null
if ($LASTEXITCODE -eq 0) {
    gh release upload $tag $asset --clobber
} else {
    gh release create $tag $asset --title "Календарь группы $version" --notes $notes
}
gh release view $tag --json url --jq .url

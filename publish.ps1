$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$gradle = Get-Content -LiteralPath "$root\app\build.gradle.kts" -Raw
if ($gradle -notmatch 'val releaseVersion = "([^"]+)"') {
    throw "В app\build.gradle.kts не найдена releaseVersion"
}
$version = $Matches[1]
$apk = Get-ChildItem -LiteralPath "$root\app\build\outputs\apk\release" -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $apk) {
    throw "Release APK ещё нет. Сначала соберите: .\gradlew.bat assembleRelease"
}

$tag = "v$version"
$asset = Join-Path $env:TEMP "group-calendar-$version.apk"
Copy-Item -LiteralPath $apk.FullName -Destination $asset -Force
$notes = @"
Version $version, release-signed. No REQUEST_INSTALL_PACKAGES. Play Protect treated in-app APK install as malware.

Uninstall the old build first, then install this one. Group data stays in the cloud. Later updates open GitHub in the browser.
"@

gh release view $tag *> $null
if ($LASTEXITCODE -eq 0) {
    gh release upload $tag $asset --clobber
} else {
    gh release create $tag $asset --title "group-calendar $version" --notes $notes
}
gh release view $tag --json url --jq .url

Set-Location $PSScriptRoot
Start-Process "http://127.0.0.1:8787/"
python -m http.server 8787

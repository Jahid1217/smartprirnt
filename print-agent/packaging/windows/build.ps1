param([string]$Python = "python")
$ErrorActionPreference = "Stop"
Push-Location (Join-Path $PSScriptRoot "../..")
try {
    foreach ($Build in @(@{ Name = "SmartPrintAgent"; Mode = "--console" },
                         @{ Name = "SmartPrintAgentBackground"; Mode = "--windowed" })) {
        $Arguments = @("--noconfirm", "--onefile", $Build.Mode, "--name", $Build.Name,
            "--paths", ".", "--collect-all", "pypdfium2", "--collect-all", "pypdfium2_raw",
            "packaging/windows/launcher.py")
        & $Python -m PyInstaller @Arguments
        if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed for $($Build.Name)" }
    }
}
finally { Pop-Location }

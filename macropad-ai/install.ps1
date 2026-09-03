<#
.SYNOPSIS
    Prepares Windows to run the MacroPad AI daemon, then hands off to install.sh.

.DESCRIPTION
    Linux containers need a Linux kernel and Windows hasn't got one, so everything runs
    inside WSL 2. This script only sets WSL up — the actual install is install.sh, run
    inside the Linux distribution.

    Enabling WSL for the first time requires a reboot. Re-run this script afterwards and
    it picks up where it left off.

    Windows is the hard way to host this. See docs/self-hosting.md.
#>

[CmdletBinding()]
param(
    [string]$Distro = "Ubuntu"
)

$ErrorActionPreference = "Stop"

function Write-Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Write-Note($text) { Write-Host "    $text" -ForegroundColor DarkGray }
function Write-Ok($text)   { Write-Host "    + $text" -ForegroundColor Green }
function Fail($text) { Write-Host "`nerror: $text`n" -ForegroundColor Red; exit 1 }

# ------------------------------------------------------------------ elevation

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Note "Installing WSL needs administrator rights. Re-launching…"
    $args = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Distro $Distro"
    Start-Process powershell -Verb RunAs -ArgumentList $args
    exit
}

Write-Host @"

  MacroPad AI - Windows setup

  This prepares WSL 2, then runs the real installer inside it.
  Windows is the least comfortable host for this; an old laptop running
  Linux, or a Mac, will give you less trouble.

"@

# ------------------------------------------------------------------ 1: WSL 2

Write-Step "WSL 2"

$wslReady = $false
try {
    # `wsl --status` fails outright when the feature is not enabled.
    wsl --status *> $null
    $wslReady = ($LASTEXITCODE -eq 0)
} catch {
    $wslReady = $false
}

if (-not $wslReady) {
    Write-Note "Enabling WSL and installing $Distro. This needs a reboot."
    wsl --install -d $Distro
    Write-Host @"

  ==> Reboot now, then run this script again to finish.

"@ -ForegroundColor Yellow
    exit 0
}
Write-Ok "WSL 2 is available"

$installed = (wsl --list --quiet) -replace "`0", "" | ForEach-Object { $_.Trim() }
if ($installed -notcontains $Distro) {
    Write-Note "Installing the $Distro distribution…"
    wsl --install -d $Distro
    Write-Note "Finish creating your Linux username and password, then re-run this script."
    exit 0
}
Write-Ok "$Distro is installed"

# --------------------------------------------------------- 2: hand off to Linux

Write-Step "Running the installer inside $Distro"

# Translate this directory into the path WSL sees, so the script runs against this
# checkout rather than a second copy.
$repo = Split-Path -Parent $PSCommandPath
$wslPath = (wsl --distribution $Distro wslpath -a "'$repo'" 2>$null)
if (-not $wslPath) {
    $drive = ($repo.Substring(0,1)).ToLower()
    $wslPath = "/mnt/$drive" + ($repo.Substring(2) -replace '\\','/')
}
$wslPath = $wslPath.Trim()

Write-Note "Repository at $wslPath"
wsl --distribution $Distro --cd "$wslPath" -- bash ./install.sh
if ($LASTEXITCODE -ne 0) { Fail "The installer did not finish. Re-run this script to continue." }

# ------------------------------------------------------------ 3: the rough edge

Write-Step "Keeping it running"

Write-Note "Windows does not start WSL at boot on its own, so the server will not"
Write-Note "come back by itself after a restart. This registers a scheduled task"
Write-Note "that starts it for you."

$taskName = "MacroPad AI"
$existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Ok "Startup task already registered"
} else {
    $action = New-ScheduledTaskAction -Execute "wsl.exe" `
        -Argument "--distribution $Distro --cd `"$wslPath`" -- docker compose up -d"
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries -StartWhenAvailable
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
        -Settings $settings -RunLevel Highest -Force | Out-Null
    Write-Ok "Registered `"$taskName`" to start at boot"
}

Write-Host @"

  Done.

  setup code   wsl -d $Distro --cd "$wslPath" -- docker compose exec macropad-ai python -m app.cli setup-qr
  logs         wsl -d $Distro --cd "$wslPath" -- docker compose logs -f macropad-ai

"@ -ForegroundColor Green

<#
InstaLogger Windows Controller
#>

param (
    [Parameter(Position=0)]
    [ValidateSet("start", "stop", "restart", "status", "logs", "help")]
    [string]$Command = "help"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

$PidFile = Join-Path $ScriptDir "run\instalogger.pid"
$LogFile = Join-Path $ScriptDir "logs\instalogger.log"
$BinRelease = Join-Path $ScriptDir "backend\target\release\instalogger_backend.exe"
$BinDebug = Join-Path $ScriptDir "backend\target\debug\instalogger_backend.exe"

New-Item -ItemType Directory -Force -Path (Join-Path $ScriptDir "data") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $ScriptDir "archives") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $ScriptDir "sessions") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $ScriptDir "logs") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $ScriptDir "run") | Out-Null

function Get-InstaBinary {
    if (Test-Path $BinRelease) { return $BinRelease }
    if (Test-Path $BinDebug) { return $BinDebug }
    return $null
}

function Start-InstaLogger {
    Write-Host "[*] Checking InstaLogger daemon status..." -ForegroundColor Cyan
    if (Test-Path $PidFile) {
        $existingPid = Get-Content $PidFile -ErrorAction SilentlyContinue
        if ($existingPid -and (Get-Process -Id $existingPid -ErrorAction SilentlyContinue)) {
            Write-Host "[!] InstaLogger is already running (PID: $existingPid)." -ForegroundColor Yellow
            return
        } else {
            Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
        }
    }

    $bin = Get-InstaBinary
    if (-not $bin) {
        Write-Host "[!] Binary not found. Compiling Rust backend..." -ForegroundColor Yellow
        Push-Location (Join-Path $ScriptDir "backend")
        cargo build --release
        Pop-Location
        $bin = Get-InstaBinary
    }

    Write-Host "[+] Starting InstaLogger daemon in background..." -ForegroundColor Green
    $process = Start-Process -FilePath $bin -WorkingDirectory $ScriptDir -RedirectStandardOutput $LogFile -RedirectStandardError $LogFile -PassThru
    $process.Id | Out-File -FilePath $PidFile -Encoding ascii

    Write-Host "[✓] InstaLogger started (PID: $($process.Id))." -ForegroundColor Green
    Write-Host "[*] Web dashboard: http://localhost:3000" -ForegroundColor Cyan
    Write-Host "[*] Logs: $LogFile" -ForegroundColor Gray
}

function Stop-InstaLogger {
    Write-Host "[*] Stopping InstaLogger daemon..." -ForegroundColor Cyan
    if (-not (Test-Path $PidFile)) {
        Write-Host "[-] InstaLogger is not running." -ForegroundColor Yellow
        return
    }

    $targetPid = Get-Content $PidFile -ErrorAction SilentlyContinue
    if ($targetPid) {
        try {
            Stop-Process -Id $targetPid -Force -ErrorAction SilentlyContinue
            Write-Host "[✓] Process $targetPid stopped." -ForegroundColor Green
        } catch {}
    }
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
}

function Get-InstaStatus {
    if (Test-Path $PidFile) {
        $p = Get-Content $PidFile -ErrorAction SilentlyContinue
        if ($p -and (Get-Process -Id $p -ErrorAction SilentlyContinue)) {
            Write-Host "[✓] InstaLogger is Running (PID: $p)" -ForegroundColor Green
            try {
                $health = Invoke-RestMethod -Uri "http://127.0.0.1:3000/api/health" -TimeoutSec 2
                Write-Host "[*] Health: $($health | ConvertTo-Json -Compress)" -ForegroundColor Gray
            } catch {
                Write-Host "[!] Web API starting..." -ForegroundColor Yellow
            }
            return
        }
    }
    Write-Host "[-] InstaLogger is Stopped." -ForegroundColor Yellow
}

function Show-InstaLogs {
    if (Test-Path $LogFile) {
        Get-Content $LogFile -Tail 50 -Wait
    } else {
        Write-Host "[-] Log file not found at $LogFile" -ForegroundColor Yellow
    }
}

switch ($Command) {
    "start"   { Start-InstaLogger }
    "stop"    { Stop-InstaLogger }
    "restart" { Stop-InstaLogger; Start-Sleep -Seconds 1; Start-InstaLogger }
    "status"  { Get-InstaStatus }
    "logs"    { Show-InstaLogs }
    default {
        Write-Host "Usage: .\instalogger.ps1 {start|stop|restart|status|logs}" -ForegroundColor Cyan
    }
}

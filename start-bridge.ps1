# Antigravity Remote - bridge launcher for Windows.
# Updates itself from GitHub, installs what it needs, opens a tunnel so the phone works from
# anywhere, and starts the bridge. Double-click start-bridge.bat to run it.
param(
    [int]$Port = 8765,
    [string]$Workspace = "",
    [string]$Name = "",
    [string]$PublicUrl = "",
    [switch]$NoTunnel,
    [switch]$NoUpdate,
    [switch]$ResetToken,
    [switch]$SkipFirewall,
    # Kept so older shortcuts keep working; the tunnel is on by default now.
    [switch]$Tunnel
)

$ErrorActionPreference = "Stop"
$RepoDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BridgeDir = Join-Path $RepoDir "bridge"
$RuleName = "Antigravity Remote Bridge (TCP $Port)"

function Write-Step([string]$Message) { Write-Host "[bridge] $Message" -ForegroundColor Cyan }
function Write-Warn([string]$Message) { Write-Host "[bridge] $Message" -ForegroundColor Yellow }

function Update-SessionPath {
    $machine = [Environment]::GetEnvironmentVariable("Path", "Machine")
    $user = [Environment]::GetEnvironmentVariable("Path", "User")
    $env:Path = "$machine;$user"
}

# 1. Update from GitHub (only for a git clone with no local changes)
if (-not $NoUpdate -and (Test-Path (Join-Path $RepoDir ".git")) -and (Get-Command git -ErrorAction SilentlyContinue)) {
    $dirty = & git -C $RepoDir status --porcelain --untracked-files=no 2>$null
    if ($dirty) {
        Write-Warn "Local changes found; skipping the automatic update."
    } else {
        Write-Step "Checking GitHub for updates..."
        $before = & git -C $RepoDir rev-parse HEAD 2>$null
        & git -C $RepoDir pull --ff-only --quiet 2>$null
        if ($LASTEXITCODE -ne 0) {
            Write-Warn "Could not update (offline?). Starting the current version."
        } elseif ((& git -C $RepoDir rev-parse HEAD 2>$null) -ne $before) {
            Write-Step "Updated to the latest version."
        }
    }
}

# 2. Node.js 18 or newer
$node = Get-Command node -ErrorAction SilentlyContinue
if (-not $node) {
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Write-Step "Node.js not found. Installing Node.js LTS with winget..."
        winget install --id OpenJS.NodeJS.LTS --exact --silent --accept-package-agreements --accept-source-agreements
        Update-SessionPath
        $node = Get-Command node -ErrorAction SilentlyContinue
    }
    if (-not $node) {
        Write-Host "[bridge] Node.js 18+ is required. Install it from https://nodejs.org and run this again." -ForegroundColor Red
        exit 1
    }
}
$nodeMajor = [int]((& node --version).TrimStart("v").Split(".")[0])
if ($nodeMajor -lt 18) {
    Write-Host "[bridge] Node.js $(& node --version) is too old. Install Node.js 18 or newer." -ForegroundColor Red
    exit 1
}

Set-Location $BridgeDir

# 3. Dependencies (reinstalled when package-lock.json changes)
$lockFile = Join-Path $BridgeDir "package-lock.json"
$stampFile = Join-Path $BridgeDir "node_modules\.install-stamp"
$needInstall = -not (Test-Path $stampFile)
if (-not $needInstall -and (Test-Path $lockFile)) {
    $needInstall = (Get-Item $lockFile).LastWriteTimeUtc -gt (Get-Item $stampFile).LastWriteTimeUtc
}
if ($needInstall) {
    Write-Step "Installing dependencies..."
    if (Test-Path $lockFile) { npm ci --no-audit --no-fund } else { npm install --no-audit --no-fund }
    if ($LASTEXITCODE -ne 0) { Write-Host "[bridge] npm install failed." -ForegroundColor Red; exit 1 }
    New-Item -ItemType File -Force -Path $stampFile | Out-Null
}

# 4. Build when sources are newer than the compiled output
$entry = Join-Path $BridgeDir "dist\index.js"
$needBuild = -not (Test-Path $entry)
if (-not $needBuild) {
    $builtAt = (Get-Item $entry).LastWriteTimeUtc
    $newest = Get-ChildItem (Join-Path $BridgeDir "src") -Recurse -File | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    $needBuild = $newest -and $newest.LastWriteTimeUtc -gt $builtAt
}
if ($needBuild) {
    Write-Step "Building bridge..."
    npm run build
    if ($LASTEXITCODE -ne 0) { Write-Host "[bridge] Build failed." -ForegroundColor Red; exit 1 }
}

# 5. Firewall rule for the home network (one UAC prompt the first time). Local subnet and Tailscale only.
$rule = Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue
if (-not $rule -and -not $SkipFirewall) {
    Write-Step "Opening Windows Firewall port $Port for your home network (approve the UAC prompt)..."
    $ruleCommand = "New-NetFirewallRule -DisplayName '$RuleName' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port -RemoteAddress LocalSubnet,100.64.0.0/10 -Profile Any | Out-Null"
    try {
        $elevated = Start-Process powershell.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden -ArgumentList @("-NoProfile", "-Command", $ruleCommand)
        if ($elevated.ExitCode -eq 0) {
            Write-Step "Firewall rule added."
        } else {
            Write-Warn "Firewall rule was not added (exit $($elevated.ExitCode)). At home the phone may not connect directly; the tunnel still works."
        }
    } catch {
        Write-Warn "Firewall rule skipped: $($_.Exception.Message)."
    }
}

# 6. Cloudflare quick tunnel: an https address that reaches this PC from anywhere,
#    with no account, VPN or router setup. The phone learns each new address by itself.
$tunnelProc = $null
$tunnelUrl = ""
$tunnelLog = $null
if (-not $NoTunnel -and -not $PublicUrl) {
    $cf = Get-Command cloudflared -ErrorAction SilentlyContinue
    if (-not $cf -and (Get-Command winget -ErrorAction SilentlyContinue)) {
        Write-Step "Installing Cloudflare Tunnel (cloudflared) for access from anywhere..."
        winget install --id Cloudflare.cloudflared --exact --silent --accept-package-agreements --accept-source-agreements | Out-Null
        Update-SessionPath
        $cf = Get-Command cloudflared -ErrorAction SilentlyContinue
        if (-not $cf) {
            $installed = Get-ChildItem "$env:ProgramFiles*\cloudflared\cloudflared.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($installed) { $cf = Get-Command $installed.FullName }
        }
    }
    if (-not $cf) {
        Write-Warn "cloudflared is not available, so the phone will only connect at home or over Tailscale."
    } else {
        $tunnelLog = Join-Path $env:TEMP ("antigravity-tunnel-" + [guid]::NewGuid().ToString("N") + ".log")
        Write-Step "Opening the tunnel..."
        $tunnelProc = Start-Process -FilePath $cf.Source `
            -ArgumentList @("tunnel", "--url", "http://127.0.0.1:$Port", "--no-autoupdate") `
            -RedirectStandardError $tunnelLog `
            -PassThru -WindowStyle Hidden
        $deadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $deadline) {
            if ($tunnelProc.HasExited) { break }
            $rawLog = Get-Content $tunnelLog -Raw -ErrorAction SilentlyContinue
            if ($rawLog -match "https://[a-z0-9-]+\.trycloudflare\.com") {
                $tunnelUrl = $Matches[0]
                break
            }
            Start-Sleep -Milliseconds 400
        }
        if (-not $tunnelUrl) {
            Write-Warn "The tunnel did not start, so the phone will only connect at home or over Tailscale."
        }
    }
}

# 7. Start
$nodeArgs = @("dist\index.js", "--port", "$Port")
if ($Workspace) { $nodeArgs += @("--workspace", $Workspace) }
if ($Name) { $nodeArgs += @("--name", $Name) }
if ($tunnelUrl) { $nodeArgs += @("--url", $tunnelUrl) }
elseif ($PublicUrl) { $nodeArgs += @("--url", $PublicUrl) }
if ($ResetToken) { $nodeArgs += "--reset-token" }
try {
    & node @nodeArgs
    exit $LASTEXITCODE
} finally {
    if ($tunnelProc -and -not $tunnelProc.HasExited) {
        Stop-Process -Id $tunnelProc.Id -Force -ErrorAction SilentlyContinue
    }
    if ($tunnelLog -and (Test-Path $tunnelLog)) {
        Remove-Item -Force -ErrorAction SilentlyContinue $tunnelLog
    }
}

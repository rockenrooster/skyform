# Swipe smoothness on a phone: opens the installed test app (build it with -Config staging) holding the display at its
# top refresh rate, swipes between places and reports frame times from gfxinfo framestats. Starts only when the
# launcher or Skyform is in front; leaves the phone on the home screen.
# -Cold: the first swipes after a fresh launch (the app is stopped first, nothing warmed up); otherwise the app is
# warmed up with 8 swipes that are not measured.
#   scripts\swipe-test.ps1 -Device op12r [-Swipes 10] [-Cold] [-Force]
param(
    [Parameter(Mandatory)][string]$Device,
    [int]$Swipes = 10,
    [switch]$Cold,
    [switch]$Force   # the owner said to go ahead although another app is in front
)
. "$PSScriptRoot\env.ps1"
$s = Resolve-TfSerial $Device
$pkg = $TF_APP_ID

function Get-Pct([double[]]$v, [double]$p) { $o = $v | Sort-Object; $o[[math]::Min($o.Count - 1, [int][math]::Floor($p * $o.Count))] }

Enter-TfDeviceLock $s -WaitSeconds 60
$started = $false
try {
    if (-not $Force -and -not (Test-TfDeviceFree $s)) { throw "not started: $(Get-TfForegroundPackage $s) is in front" }
    if ($Cold) { Stop-TfApp $s }   # our own test app only
    $launch = & $TF_ADB -s $s shell am start -W -n "$pkg/dev.skyform.weather.MainActivity" --ez hz120 true
    $started = $true
    "launch: " + (($launch | Select-String 'LaunchState|TotalTime') -join ', ')
    if ($Cold) {
        Start-Sleep 2   # about when a person would first swipe
    } else {
        Start-Sleep 6
        foreach ($i in 1..4) {   # warm-up (JIT, first compositions), not measured
            & $TF_ADB -s $s shell input swipe 950 700 130 700 220; Start-Sleep -Milliseconds 800
            & $TF_ADB -s $s shell input swipe 130 700 950 700 220; Start-Sleep -Milliseconds 800
        }
    }
    $rows = @()
    $gaps = @()
    $janky = 0; $total = 0   # Android's own count: frames that missed their display deadline
    for ($i = 0; $i -lt $Swipes; $i++) {
        & $TF_ADB -s $s shell dumpsys gfxinfo $pkg reset | Out-Null
        if ($i % 2) { & $TF_ADB -s $s shell input swipe 130 700 950 700 220 } else { & $TF_ADB -s $s shell input swipe 950 700 130 700 220 }
        Start-Sleep -Milliseconds 700
        $raw = & $TF_ADB -s $s shell dumpsys gfxinfo $pkg framestats
        $tl = $raw | Select-String '^Total frames rendered: (\d+)' | Select-Object -First 1
        if ($tl) { $total += [int]$tl.Matches[0].Groups[1].Value }
        $jl = $raw | Select-String '^Janky frames: (\d+)' | Select-Object -First 1
        if ($jl) { $janky += [int]$jl.Matches[0].Groups[1].Value }
        $h = $raw | Select-String '^Flags,' | Select-Object -First 1
        if (-not $h) { "swipe ${i}: no frames"; continue }
        $hdr = $h.Line.Split(',')
        $ix = @{}; for ($k = 0; $k -lt $hdr.Length; $k++) { $ix[$hdr[$k]] = $k }
        $prev = 0
        foreach ($line in ($raw | Where-Object { $_ -match '^0,' })) {   # flags 0: ordinary frames
            $c = $line.Split(',')
            $v = [int64]$c[$ix['IntendedVsync']]
            $end = [math]::Max([int64]$c[$ix['FrameCompleted']], [int64]$c[$ix['GpuCompleted']])
            $rows += [pscustomobject]@{
                # in motion: drawn one refresh after the previous frame (the swipe and its settling, not the sky's
                # 30-a-second idle frames)
                motion = $prev -gt 0 -and ($v - $prev) -lt 12e6
                # the frame's own work: vsync to GPU done, less time blocked waiting for a free buffer
                work = ($end - $v - [int64]$c[$ix['DequeueBufferDuration']]) / 1e6
                ui   = ([int64]$c[$ix['SyncQueued']] - $v) / 1e6
                gpu  = ($end - [int64]$c[$ix['IssueDrawCommandsStart']]) / 1e6
            }
            if ($prev -gt 0) { $gaps += ($v - $prev) / 1e6 }
            $prev = $v
        }
    }
    $hz = 1000 / (Get-Pct $gaps 0.5)
    "{0} frames at {1:N0} Hz (budget {2:N2} ms); Android's count: {3} of {4} missed their deadline" -f $rows.Count, $hz, (1000 / 120), $janky, $total
    foreach ($set in @(@{ name = 'all frames'; r = $rows }, @{ name = 'in motion'; r = @($rows | Where-Object motion) })) {
        "{0}: {1} frames, over 8.33 ms: {2}" -f $set.name, $set.r.Count, @($set.r | Where-Object { $_.work -gt 8.333 }).Count
        foreach ($m in 'work', 'ui', 'gpu') {
            $v = [double[]]($set.r | ForEach-Object { $_.$m })
            "  {0,-5} p50 {1,5:N2}  p90 {2,5:N2}  p95 {3,5:N2}  p99 {4,6:N2}  max {5,6:N2} ms" -f $m, (Get-Pct $v 0.5), (Get-Pct $v 0.9), (Get-Pct $v 0.95), (Get-Pct $v 0.99), ($v | Measure-Object -Maximum).Maximum
        }
    }
} finally {
    if ($started) { & $TF_ADB -s $s shell input keyevent KEYCODE_HOME }
    Exit-TfDeviceLock $s
}

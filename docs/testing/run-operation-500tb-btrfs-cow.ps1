param(
    [ValidateRange(16, 4096)]
    [int]$SampleMiB = 256,
    [ValidateRange(1, 1024)]
    [int]$MutationMiB = 4
)

$ErrorActionPreference = 'Stop'
$wsl = 'C:\Windows\System32\wsl.exe'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$wslRepo = (& $wsl -d Ubuntu-24.04 -u root -- wslpath -a $repo).Trim()

& $wsl -d Ubuntu-24.04 -u root -- bash "$wslRepo/docs/testing/setup-operation-500tb-cow-lab.sh"
if ($LASTEXITCODE -ne 0) { throw 'COW lab setup failed.' }

& $wsl -d Ubuntu-24.04 -u root -- env `
    "FORGETDM_COW_SAMPLE_MIB=$SampleMiB" `
    "FORGETDM_COW_MUTATION_MIB=$MutationMiB" `
    bash "$wslRepo/docs/testing/run-operation-500tb-btrfs-cow.sh"
if ($LASTEXITCODE -ne 0) { throw 'Real COW qualification failed.' }

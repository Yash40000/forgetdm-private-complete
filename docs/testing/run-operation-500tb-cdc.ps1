param(
    [string]$JdbcUrl = "jdbc:postgresql://localhost:5433/forgetdm",
    [string]$User = "forgetdm",
    [string]$Password = $env:FORGETDM_CDC_TEST_PASS
)
$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($Password)) {
    throw "Set FORGETDM_CDC_TEST_PASS or pass -Password before running CDC qualification."
}
$repo = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$maven = if (Test-Path "C:\Tools\apache-maven-3.9.16\bin\mvn.cmd") { "C:\Tools\apache-maven-3.9.16\bin\mvn.cmd" } else { (Get-Command mvn.cmd -ErrorAction Stop).Source }
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$root = Join-Path $repo "docs/testing/evidence/operation-500tb-cdc"
$evidence = Join-Path $root $stamp
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$env:FORGETDM_CDC_TEST_URL = $JdbcUrl
$env:FORGETDM_CDC_TEST_USER = $User
$env:FORGETDM_CDC_TEST_PASS = $Password
Push-Location $repo
try {
    $tests = "io.forgetdm.cdc.Operation500TbCdcQualificationTest,io.forgetdm.cdc.Operation500TbCdcPostgresIntegrationTest,io.forgetdm.cdc.CdcContinuousPollingTest,io.forgetdm.cdc.CdcIncrementalApplierTimeFlowTest,io.forgetdm.cdc.CdcTimeFlowServiceTest,io.forgetdm.cdc.OracleCdcProviderTest"
    & $maven "-Dtest=$tests" "-Dforgetdm.cdc.evidence.dir=$evidence" test
    if ($LASTEXITCODE -ne 0) { throw "CDC qualification failed ($LASTEXITCODE)" }
    Copy-Item (Join-Path $evidence "operation-500tb-cdc-report.md") (Join-Path $root "latest-report.md") -Force
    Copy-Item (Join-Path $evidence "operation-500tb-cdc-report.json") (Join-Path $root "latest-report.json") -Force
    Copy-Item (Join-Path $evidence "postgres-real-cdc-report.md") (Join-Path $root "latest-postgres-report.md") -Force
    Copy-Item (Join-Path $evidence "postgres-real-cdc-report.json") (Join-Path $root "latest-postgres-report.json") -Force
    Write-Host "CDC evidence: $evidence"
} finally { Pop-Location }

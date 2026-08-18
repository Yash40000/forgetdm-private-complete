param(
  [int]$Minutes = 90,
  [int]$IntervalMinutes = 10,
  [int]$BatchRows = 100000,
  [string]$PostgresUrl = $(if ($env:FORGETDM_LOAD_PG_URL) { $env:FORGETDM_LOAD_PG_URL } else { 'jdbc:postgresql://localhost:5433/forgetdm' }),
  [string]$PostgresUser = $(if ($env:FORGETDM_LOAD_PG_USER) { $env:FORGETDM_LOAD_PG_USER } else { 'forgetdm' }),
  [string]$PostgresPassword = $env:FORGETDM_LOAD_PG_PASS,
  [string]$OracleUrl = $(if ($env:FORGETDM_LOAD_ORACLE_URL) { $env:FORGETDM_LOAD_ORACLE_URL } else { 'jdbc:oracle:thin:@localhost:1521:XE' }),
  [string]$OracleUser = $env:FORGETDM_LOAD_ORACLE_USER,
  [string]$OraclePassword = $env:FORGETDM_LOAD_ORACLE_PASS,
  [string]$OracleSqlLoader = $env:FORGETDM_ORACLE_SQLLOADER_BIN,
  [string]$OracleConnect = $(if ($env:FORGETDM_ORACLE_SQLLOADER_CONNECT) { $env:FORGETDM_ORACLE_SQLLOADER_CONNECT } else { 'XE' })
)
$ErrorActionPreference = "Stop"
foreach ($setting in @{
  FORGETDM_LOAD_PG_PASS = $PostgresPassword
  FORGETDM_LOAD_ORACLE_USER = $OracleUser
  FORGETDM_LOAD_ORACLE_PASS = $OraclePassword
  FORGETDM_ORACLE_SQLLOADER_BIN = $OracleSqlLoader
}.GetEnumerator()) {
  if ([string]::IsNullOrWhiteSpace([string]$setting.Value)) {
    throw "Set $($setting.Key) or pass the corresponding script parameter."
  }
}
$repo = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$root = Join-Path $repo "docs/testing/evidence/operation-500tb-rolling-endurance"
$evidence = Join-Path $root $stamp
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$env:FORGETDM_LOAD_PG_URL=$PostgresUrl; $env:FORGETDM_LOAD_PG_USER=$PostgresUser; $env:FORGETDM_LOAD_PG_PASS=$PostgresPassword
$env:FORGETDM_LOAD_ORACLE_URL=$OracleUrl; $env:FORGETDM_LOAD_ORACLE_USER=$OracleUser; $env:FORGETDM_LOAD_ORACLE_PASS=$OraclePassword
$env:FORGETDM_ORACLE_SQLLOADER_ENABLED='true'; $env:FORGETDM_ORACLE_SQLLOADER_BIN=$OracleSqlLoader
$env:FORGETDM_ORACLE_SQLLOADER_CONNECT=$OracleConnect; $env:FORGETDM_ORACLE_SQLLOADER_AUTH='PASSWORD_STDIN'; $env:FORGETDM_NATIVE_LOAD_EVIDENCE_DIR=(Join-Path $evidence 'oracle')
Push-Location $repo
try {
  & 'C:\Tools\apache-maven-3.9.16\bin\mvn.cmd' '-Dtest=io.forgetdm.provision.loader.Operation500TbRollingEnduranceTest' "-Dforgetdm.endurance.minutes=$Minutes" "-Dforgetdm.endurance.intervalMinutes=$IntervalMinutes" "-Dforgetdm.endurance.batchRows=$BatchRows" "-Dforgetdm.endurance.evidence.dir=$evidence" test
  if($LASTEXITCODE-ne 0){throw "Rolling endurance failed ($LASTEXITCODE)"}
  Copy-Item (Join-Path $evidence 'operation-500tb-rolling-endurance.md') (Join-Path $root 'latest-report.md') -Force
  Copy-Item (Join-Path $evidence 'operation-500tb-rolling-endurance.json') (Join-Path $root 'latest-report.json') -Force
  Write-Host "Rolling endurance evidence: $evidence"
} finally { Pop-Location }

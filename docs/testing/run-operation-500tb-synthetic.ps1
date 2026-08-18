param(
    [ValidateRange(100000, 5000000)]
    [long]$SampleRows = 1000000,
    [string]$EvidenceRoot = "docs/testing/evidence/operation-500tb-synthetic"
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$maven = if ($env:MAVEN_HOME) {
    Join-Path $env:MAVEN_HOME "bin/mvn.cmd"
} elseif (Test-Path "C:\Tools\apache-maven-3.9.16\bin\mvn.cmd") {
    "C:\Tools\apache-maven-3.9.16\bin\mvn.cmd"
} else {
    (Get-Command mvn.cmd -ErrorAction Stop).Source
}
$git = if (Test-Path "C:\Program Files\Git\cmd\git.exe") {
    "C:\Program Files\Git\cmd\git.exe"
} else {
    (Get-Command git.exe -ErrorAction Stop).Source
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$evidenceDir = Join-Path $repo (Join-Path $EvidenceRoot $stamp)
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
$commit = (& $git -C $repo rev-parse --short=12 HEAD).Trim()

Push-Location $repo
try {
    $testClasses = @(
        "io.forgetdm.provision.Operation500TbSyntheticQualificationTest",
        "io.forgetdm.provision.SyntheticPartitioningTest",
        "io.forgetdm.provision.SyntheticGeneratorSeedParityTest",
        "io.forgetdm.provision.SyntheticBankingReadinessTest",
        "io.forgetdm.provision.SyntheticMultiSystemTargetTest",
        "io.forgetdm.provision.SyntheticConstraintRulesTest",
        "io.forgetdm.provision.SyntheticDataSafetyTest",
        "io.forgetdm.provision.SyntheticPartitionAndSavedJobAuditTest"
    )
    & $maven `
        ("-Dtest=" + ($testClasses -join ",")) `
        "-Dforgetdm.synthetic.scale.sampleRows=$SampleRows" `
        "-Dforgetdm.synthetic.scale.evidenceDir=$evidenceDir" `
        "-Dforgetdm.synthetic.scale.commit=$commit" `
        test
    if ($LASTEXITCODE -ne 0) { throw "Operation 500 TB synthetic qualification failed with exit code $LASTEXITCODE" }

    $totals = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0 }
    foreach ($className in $testClasses) {
        $xmlPath = Join-Path $repo ("target/surefire-reports/TEST-" + $className + ".xml")
        if (-not (Test-Path -LiteralPath $xmlPath)) { throw "Missing Surefire evidence: $xmlPath" }
        [xml]$suite = Get-Content -LiteralPath $xmlPath -Raw
        $totals.tests += [int]$suite.testsuite.tests
        $totals.failures += [int]$suite.testsuite.failures
        $totals.errors += [int]$suite.testsuite.errors
        $totals.skipped += [int]$suite.testsuite.skipped
    }

    $jsonPath = Join-Path $evidenceDir "operation-500tb-synthetic-report.json"
    $json = Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json
    $json | Add-Member -NotePropertyName focusedRegression -NotePropertyValue ([pscustomobject]@{
        status = "PASS"
        testClasses = $testClasses.Count
        tests = $totals.tests
        failures = $totals.failures
        errors = $totals.errors
        skipped = $totals.skipped
    }) -Force
    $json | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $jsonPath -Encoding utf8

    $markdownPath = Join-Path $evidenceDir "operation-500tb-synthetic-report.md"
    Add-Content -LiteralPath $markdownPath -Encoding utf8 -Value @"

## Focused synthetic regression

$($totals.tests) tests across $($testClasses.Count) qualification and synthetic safety classes passed with
$($totals.failures) failures, $($totals.errors) errors, and $($totals.skipped) skipped tests.
"@

    Copy-Item -LiteralPath $markdownPath `
        -Destination (Join-Path $repo (Join-Path $EvidenceRoot "latest-report.md")) -Force
    Copy-Item -LiteralPath $jsonPath `
        -Destination (Join-Path $repo (Join-Path $EvidenceRoot "latest-report.json")) -Force
    Write-Host "Operation 500 TB synthetic evidence: $evidenceDir"
} finally {
    Pop-Location
}

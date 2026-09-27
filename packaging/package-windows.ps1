<#
.SYNOPSIS
  Builds a self contained Windows application: a jlink runtime image with the JavaFX modules inside it,
  wrapped by jpackage so that the user does not need Java or JavaFX installed.

.DESCRIPTION
  Produces an app-image by default (a folder with StegSolver.exe and the bundled runtime). Pass
  -Type msi to build an installer instead; that additionally requires the WiX toolset on the build
  machine.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File packaging\package-windows.ps1
  powershell -ExecutionPolicy Bypass -File packaging\package-windows.ps1 -Type msi -SkipTests
#>
[CmdletBinding()]
param(
    [ValidateSet('app-image', 'msi', 'exe')]
    [string]$Type = 'app-image',
    [string]$Version = '',
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $projectDir

if (-not $Version) {
    $Version = (Select-String -Path 'pom.xml' -Pattern '<version>([^<]+)</version>' |
        Select-Object -First 1).Matches.Groups[1].Value
}

# jpackage accepts only a numeric version, so a development version such as 1.0.0-SNAPSHOT becomes 1.0.0.
$packageVersion = ($Version -replace '-.*$', '')
if ($packageVersion -notmatch '^[0-9]+(\.[0-9]+){0,2}$') {
    throw "Cannot derive a jpackage version from '$Version' (need something like 1.0.0 or 1.0.0-SNAPSHOT)"
}

$appName = 'StegSolver'

<#
.SYNOPSIS
  Runs the packaged launcher and returns its exit code.

.DESCRIPTION
  jpackage produces a Windows GUI-subsystem executable. PowerShell's call operator does not wait for
  GUI applications, so $LASTEXITCODE is meaningless for them - it either keeps the value of an earlier
  command or is empty, which made the verification fail even though the application had run correctly.
  Start-Process -Wait -PassThru waits for the real process and reports its exit code, and -NoNewWindow
  keeps the launcher's console output attached.
#>
function Invoke-PackagedApp {
    param(
        [Parameter(Mandatory = $true)][string]$Executable,
        [string[]]$Arguments = @()
    )
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -Wait -PassThru -NoNewWindow
    return $process.ExitCode
}
$mainClass = 'io.github.jacek4yang.stegsolver.Launcher'
$platform = 'win'
$javaModules = 'java.base,java.desktop,java.logging,java.xml,java.prefs,java.datatransfer,java.scripting,jdk.charsets'
$javaFxModules = 'javafx.base,javafx.graphics,javafx.controls'

$distDir = 'target\dist'
$inputDir = Join-Path $distDir 'input'
$moduleDir = Join-Path $distDir 'javafx-modules'
$runtimeDir = Join-Path $distDir 'runtime'
$packageDir = Join-Path $distDir 'packages'

Write-Host '==> Checking the toolchain'
foreach ($tool in @('java', 'jlink', 'jpackage', 'mvn')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "$tool was not found on PATH. A JDK 21 (with jlink and jpackage) and Maven 3.9+ are required."
    }
}
# java -version writes to stderr, which PowerShell would turn into a terminating error.
$javaVersion = cmd /c "java -version 2>&1" | Select-Object -First 1
Write-Host "    $javaVersion"

Write-Host "==> Cleaning $distDir"
if (Test-Path $distDir) { Remove-Item -Recurse -Force $distDir }
New-Item -ItemType Directory -Force -Path $inputDir, $moduleDir, $runtimeDir, $packageDir | Out-Null

$testArgs = @()
if ($SkipTests) { $testArgs += '-DskipTests' }

Write-Host '==> Building the application jar'
& mvn -B -ntp @testArgs clean package
if ($LASTEXITCODE -ne 0) { throw 'The Maven build failed' }

Write-Host '==> Collecting the runtime dependencies (ZXing) and the JavaFX modules'
& mvn -B -ntp -q dependency:copy-dependencies '-DincludeScope=runtime' '-DexcludeGroupIds=org.openjfx' `
    '-DstripVersion=true' "-DoutputDirectory=$inputDir"
Copy-Item 'target\stegsolver.jar' $inputDir

& mvn -B -ntp -q dependency:copy-dependencies '-DincludeScope=runtime' '-DincludeGroupIds=org.openjfx' `
    '-DstripVersion=true' "-DoutputDirectory=$moduleDir"
# The plain JavaFX artifacts are empty stubs; only the platform ones carry the classes and the native
# libraries, and having both on the module path makes jlink fail with a duplicate module error.
Get-ChildItem $moduleDir -Filter '*.jar' | Where-Object { $_.Name -notlike "*-$platform.jar" } |
    Remove-Item -Force
Write-Host "    module path: $((Get-ChildItem $moduleDir | Select-Object -ExpandProperty Name) -join ' ')"

Write-Host '==> Creating the jlink runtime image'
& jlink --module-path $moduleDir --add-modules "$javaModules,$javaFxModules" --output $runtimeDir `
    --strip-debug --no-header-files --no-man-pages --compress=zip-6
if ($LASTEXITCODE -ne 0) { throw 'jlink failed' }

Write-Host '==> Verifying that the runtime image contains JavaFX'
$moduleList = & "$runtimeDir\bin\java" --list-modules
if (-not ($moduleList | Select-String -Pattern '^javafx\.controls')) {
    throw 'The JavaFX modules are missing from the runtime image'
}

Write-Host "==> Packaging with jpackage (type: $Type)"
$jpackageArgs = @(
    '--type', $Type,
    '--name', $appName,
    '--app-version', $packageVersion,
    '--vendor', 'jacek4yang',
    '--description', 'Steganography analysis tool (bit planes, extraction, barcodes, file analysis)',
    '--copyright', 'Copyright (c) 2025 jacek4yang; based on the original StegSolve by Caesum',
    '--input', $inputDir,
    '--main-jar', 'stegsolver.jar',
    '--main-class', $mainClass,
    '--runtime-image', $runtimeDir,
    '--dest', $packageDir,
    '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', '-Xmx2g'
)
if ($Type -eq 'msi' -or $Type -eq 'exe') {
    $jpackageArgs += @('--win-menu', '--win-shortcut', '--win-dir-chooser', '--win-per-user-install')
}
& jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }

Write-Host '==> Verifying the packaged application'
if ($Type -eq 'app-image') {
    # --version and --self-test both run without a display, so they work in continuous integration.
    $versionExit = Invoke-PackagedApp -Executable "$packageDir\$appName\$appName.exe" `
        -Arguments @('--version')
    if ($versionExit -ne 0) { throw "The packaged application did not start (exit code $versionExit)" }
    $selfTestExit = Invoke-PackagedApp -Executable "$packageDir\$appName\$appName.exe" `
        -Arguments @('--self-test')
    if ($selfTestExit -ne 0) {
        throw "The self test failed in the packaged application (exit code $selfTestExit)"
    }
    Write-Host '    the packaged application starts and passes the self test'
}

Write-Host ''
Write-Host 'Done. Artifacts:'
Get-ChildItem $packageDir | ForEach-Object { Write-Host "  $($_.FullName)" }
Write-Host ''
Write-Host 'Smoke test (opens a window for a moment, renders a few transforms, exits):'
Write-Host "  `$env:JAVA_TOOL_OPTIONS='-Dstegsolver.smokeTest=true'; & '$packageDir\$appName\$appName.exe' <image>"

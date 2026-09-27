<#
  Runs StegSolver straight from the sources with the normal JDK and Maven (no packaging needed).

    scripts\run.ps1                start with an empty window
    scripts\run.ps1 image.png      start with an image open
    scripts\run.ps1 --smoke        start, render a few transforms, exit (used to verify a build)
#>
[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $projectDir

$smoke = @()
$files = @()
foreach ($argument in $Arguments) {
    if ($argument -eq '--smoke') { $smoke += @('-Dstegsolver.smokeTest=true', '-Dstegsolver.smokeSteps=6') }
    elseif ($argument.StartsWith('-D')) { $smoke += $argument }
    else { $files += $argument }
}

& mvn -B -ntp -q compile dependency:build-classpath '-Dmdep.outputFile=target\cp.txt' '-Dmdep.includeScope=runtime'
if ($LASTEXITCODE -ne 0) { throw 'The Maven build failed' }

$cp = "target\classes;" + (Get-Content 'target\cp.txt' -Raw).Trim()
$javaArgs = @($smoke + @('-cp', $cp, 'io.github.jacek4yang.stegsolver.Launcher') + $files) |
    Where-Object { $_ -ne '' }
& java @javaArgs

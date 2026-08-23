param(
    [string]$AwmCommand = "awm"
)

$command = Get-Command $AwmCommand -ErrorAction Stop
& $command.Source agent inspect --json
exit $LASTEXITCODE

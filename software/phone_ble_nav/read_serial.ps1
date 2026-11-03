param([string]$Port='COM7',[int]$Seconds=18,[switch]$ResetBoard,[string]$LogPath='',[switch]$AppendLog)
$ErrorActionPreference='Stop'
$serialReader=[System.IO.Ports.SerialPort]::new($Port,115200)
$serialReader.DtrEnable=$false
$serialReader.RtsEnable=$false
$serialReader.ReadTimeout=500
$serialLog=$null
try {
    if($LogPath){$serialLog=[IO.StreamWriter]::new($LogPath,[bool]$AppendLog,[Text.UTF8Encoding]::new($false));$serialLog.AutoFlush=$true}
    $serialReader.Open()
    if($ResetBoard){
        $serialReader.RtsEnable=$true
        Start-Sleep -Milliseconds 100
        $serialReader.RtsEnable=$false
    }
    $readDeadline=[DateTime]::UtcNow.AddSeconds($Seconds)
    while([DateTime]::UtcNow -lt $readDeadline){
        $serialText=$serialReader.ReadExisting()
        if($serialText){[Console]::Write($serialText);if($serialLog){$serialLog.Write($serialText)}}
        Start-Sleep -Milliseconds 100
    }
} finally {
    if($serialReader.IsOpen){$serialReader.Close()}
    $serialReader.Dispose()
    if($serialLog){$serialLog.Dispose()}
}

# 在本机验证高德 Web 服务「驾车路线规划 2.0」。
# Key 只从同目录 amap_key.txt 读取；输出中不包含 Key 或完整请求 URL。
$ErrorActionPreference = 'Stop'
$keyPath = Join-Path $PSScriptRoot 'amap_key.txt'
if (-not (Test-Path -LiteralPath $keyPath)) {
    Write-Output '未找到 amap_key.txt。'
    exit 1
}

$amapKey = (Get-Content -LiteralPath $keyPath -Raw -Encoding UTF8).Trim()
if ([string]::IsNullOrWhiteSpace($amapKey) -or $amapKey -eq '在这里填写高德Web服务Key') {
    Write-Output '请先在 amap_key.txt 中填写 Web 服务 Key。'
    exit 1
}

# 公开测试点：北京天安门附近 -> 北京站附近；经度在前，纬度在后。
$origin = '116.397499,39.908722'
$destination = '116.427281,39.902667'
$escapedKey = [uri]::EscapeDataString($amapKey)
$requestUri = "https://restapi.amap.com/v5/direction/driving?key=$escapedKey&origin=$origin&destination=$destination&strategy=0&show_fields=polyline,navi"

try {
    $routeResponse = Invoke-RestMethod -Method Get -Uri $requestUri -TimeoutSec 25
} catch {
    # 不输出异常对象，避免其中包含带 Key 的请求 URL。
    Write-Output 'HTTPS 请求未完成。请检查网络、高德配额和 Key 类型。'
    exit 2
}

$status = [string]$routeResponse.status
$info = [string]$routeResponse.info
$infocode = [string]$routeResponse.infocode
Write-Output "高德状态：status=$status，info=$info，infocode=$infocode"
if ($status -ne '1') { exit 3 }

$paths = @($routeResponse.route.paths)
if ($paths.Count -eq 0) {
    Write-Output '请求成功，但没有返回路线方案。'
    exit 4
}
$steps = @($paths[0].steps)
$polylineSteps = @($steps | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.polyline) })
$naviSteps = @($steps | Where-Object { $null -ne $_.navi })
Write-Output "路线方案数：$($paths.Count)；首条路线距离：$($paths[0].distance) 米；路段数：$($steps.Count)"
Write-Output "包含折线的路段：$($polylineSteps.Count)；包含导航动作的路段：$($naviSteps.Count)"
if ($steps.Count -gt 0) {
    Write-Output "首条行驶提示：$($steps[0].instruction)"
}
if ($polylineSteps.Count -eq 0 -or $naviSteps.Count -eq 0) {
    Write-Output '接口可用，但缺少绘图或导航动作字段；请检查 show_fields 参数及 Key 权限。'
    exit 5
}
Write-Output '高德路线接口验证通过。'

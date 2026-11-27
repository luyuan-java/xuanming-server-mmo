<#
.SYNOPSIS
预发 / 生产部署前与部署后门禁脚本（对齐主仓 gate-online-identity.md §10.2~§10.4）：
1. 逐节点核对 Pod 间 WireGuard 加密公钥注解（Calico `projectcalico.org/WireguardPublicKey` 或 Cilium `network.cilium.io/wg-pub-key`），任一节点缺失直接拒签；
2. 核对目标命名空间已部署 `game-login-rpc-whitelist` 与 `game-online-services-rpc-whitelist` NetworkPolicy；
3. 可选开启 `-ProbeLive` 在集群内实测未授权 Pod 访问在线服务 Dubbo 端口被拦截。
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Namespace,
    [ValidateSet("auto", "calico", "cilium")]
    [string]$Cni = "auto",
    [switch]$ProbeLive
)

$ErrorActionPreference = "Stop"

Write-Host "[1/3] 逐节点核对 Pod 间 WireGuard 公钥注解 (CNI=$Cni)..."
$nodesJson = kubectl get nodes -o json | ConvertFrom-Json
if (-not $nodesJson.items -or $nodesJson.items.Count -eq 0) {
    throw "未读到任何 Kubernetes Node，拒绝放行部署"
}

$missingWg = @()
foreach ($node in $nodesJson.items) {
    $name = $node.metadata.name
    $ann = $node.metadata.annotations
    $calicoKey = if ($ann) { $ann.'projectcalico.org/WireguardPublicKey' } else { $null }
    $ciliumKey = if ($ann) { $ann.'network.cilium.io/wg-pub-key' } else { $null }
    if (-not $ciliumKey -and ($Cni -eq "auto" -or $Cni -eq "cilium")) {
        $cn = kubectl get ciliumnode $name -o json 2>$null | ConvertFrom-Json
        if ($cn -and $cn.metadata -and $cn.metadata.annotations) {
            $ciliumKey = $cn.metadata.annotations.'network.cilium.io/wg-pub-key'
        }
    }
    $ok = switch ($Cni) {
        "calico" { -not [string]::IsNullOrWhiteSpace($calicoKey) }
        "cilium" { -not [string]::IsNullOrWhiteSpace($ciliumKey) }
        default  { -not [string]::IsNullOrWhiteSpace($calicoKey) -or -not [string]::IsNullOrWhiteSpace($ciliumKey) }
    }
    if (-not $ok) {
        $missingWg += $name
    }
}
if ($missingWg.Count -gt 0) {
    throw "以下节点缺少 WireGuard 公钥注解（可能内核不支持或未启用透明加密），拒绝部署: $($missingWg -join ', ')"
}
Write-Host "PASS: 全部 $($nodesJson.items.Count) 个节点均已激活 WireGuard 加密公钥。"

Write-Host "[2/3] 核对命名空间 $Namespace 的在线服务端口白名单 NetworkPolicy..."
foreach ($np in @("game-login-rpc-whitelist", "game-online-services-rpc-whitelist")) {
    kubectl -n $Namespace get networkpolicy $np | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "缺少必需 NetworkPolicy: $np (namespace=$Namespace)"
    }
}
Write-Host "PASS: 在线服务 RPC 端口白名单 NetworkPolicy 已就绪。"

if ($ProbeLive) {
    Write-Host "[3/3] 实测未登记标签 Pod 访问 game-login:20881 应被 NetworkPolicy 阻断..."
    $probePod = "netpol-probe-$(Get-Random)"
    kubectl -n $Namespace run $probePod --rm -i --restart=Never --image=busybox:1.36 -- sh -c "nc -z -w 2 game-login 20881" 2>$null
    if ($LASTEXITCODE -eq 0) {
        throw "安全失败：未授权 Pod 竟然连上了 game-login:20881，NetworkPolicy 未生效！"
    }
    Write-Host "PASS: 未授权 Pod 访问 game-login:20881 已被 NetworkPolicy 拦截。"
}

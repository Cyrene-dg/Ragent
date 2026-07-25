Param(
    [string]$ApiBaseUrl = "http://localhost:9090/api/ragent",
    [string]$Username = "admin",
    [string]$Password = "admin",
    [string]$V7Root = "resources/genshin_seed_v7",
    [int]$PauseMs = 100,
    [switch]$Apply
)

$ErrorActionPreference = "Stop"

function Join-ApiUrl([string]$Path) {
    return ($ApiBaseUrl.TrimEnd("/") + "/" + $Path.TrimStart("/"))
}

function Invoke-RagentJson {
    Param([string]$Method, [string]$Path, [object]$Body = $null, [string]$Token = $null)
    $headers = @{}
    if ($Token) { $headers["Authorization"] = $Token }
    $url = Join-ApiUrl $Path
    if ($null -ne $Body) {
        $json = ConvertTo-Json -InputObject $Body -Depth 50
        $utf8Body = [System.Text.Encoding]::UTF8.GetBytes($json)
        $response = Invoke-RestMethod -Method $Method -Uri $url -Headers $headers -ContentType "application/json; charset=utf-8" -Body $utf8Body
    } else {
        $response = Invoke-RestMethod -Method $Method -Uri $url -Headers $headers
    }
    if ($response.PSObject.Properties.Name -contains "code") {
        if ($response.code -ne 0 -and $response.code -ne "0") {
            throw "API $Path failed: [$($response.code)] $($response.message)"
        }
        return $response.data
    }
    return $response
}

function Get-DocumentChunks([string]$Token, [string]$DocId) {
    $all = New-Object System.Collections.Generic.List[object]
    $current = 1
    do {
        $page = Invoke-RagentJson -Method GET -Path "/knowledge-base/docs/$DocId/chunks?current=$current&size=200" -Token $Token
        foreach ($record in @($page.records)) { $all.Add($record) }
        $current++
    } while ($all.Count -lt [int]$page.total)
    return $all.ToArray()
}

$v7RootAbs = (Resolve-Path $V7Root).Path
$chunks = Get-Content -LiteralPath (Join-Path $v7RootAbs "artifacts\chunks.jsonl") -Encoding utf8 |
    Where-Object { $_.Trim() } |
    ForEach-Object { $_ | ConvertFrom-Json }
$elementBySourceDocument = @{}
foreach ($chunk in $chunks) {
    if ($chunk.metadata.category -ne "character" -or -not $chunk.metadata.element) { continue }
    $sourceDocument = [string]$chunk.metadata.source_document
    $element = [string]$chunk.metadata.element
    if ($elementBySourceDocument.ContainsKey($sourceDocument) -and $elementBySourceDocument[$sourceDocument] -ne $element) {
        throw "Conflicting element metadata in $sourceDocument"
    }
    $elementBySourceDocument[$sourceDocument] = $element
}

$saved = Get-Content -LiteralPath (Join-Path $v7RootAbs "artifacts\import_state.json") -Raw -Encoding utf8 | ConvertFrom-Json
$docIdBySourceDocument = @{}
foreach ($property in $saved.PSObject.Properties) {
    if ($property.Value.docId) { $docIdBySourceDocument[$property.Name] = [string]$property.Value.docId }
}

Write-Host "Element backfill plan: documents=$($elementBySourceDocument.Count)"
if (-not $Apply) {
    Write-Host "Dry run only. The script will update MySQL metadata then rebuild each affected document once, which synchronizes Milvus and ES."
    exit 0
}

$login = Invoke-RagentJson -Method POST -Path "/auth/login" -Body @{ username = $Username; password = $Password }
$token = [string]$login.token
if (-not $token) { throw "Login succeeded but did not return a token" }

$processed = 0
foreach ($sourceDocument in ($elementBySourceDocument.Keys | Sort-Object)) {
    $docId = $docIdBySourceDocument[$sourceDocument]
    if (-not $docId) { throw "No runtime document ID for $sourceDocument" }
    $element = $elementBySourceDocument[$sourceDocument]
    $updates = @()
    foreach ($chunk in (Get-DocumentChunks -Token $token -DocId $docId)) {
        # Windows PowerShell 5.1 does not support ConvertFrom-Json -AsHashtable.
        $metadata = @{}
        $metadataObject = $chunk.metadataJson | ConvertFrom-Json
        foreach ($property in $metadataObject.PSObject.Properties) {
            $metadata[$property.Name] = $property.Value
        }
        if ($metadata["element"] -eq $element) { continue }
        $metadata["element"] = $element
        $updates += @{ chunkId = [string]$chunk.id; metadata = $metadata }
    }
    if ($updates.Count -gt 0) {
        [void](Invoke-RagentJson -Method PUT -Path "/knowledge-base/docs/$docId/chunks/batch-metadata" -Token $token -Body @($updates))
    }
    # Rebuilding is deliberately idempotent. If a previous execution was interrupted
    # after metadata was written, this retry still synchronizes both indexes.
    [void](Invoke-RagentJson -Method POST -Path "/knowledge-base/docs/$docId/chunks/rebuild" -Token $token)
    $processed++
    if ($processed % 20 -eq 0) { Write-Host "Rebuilt $processed / $($elementBySourceDocument.Count) character documents" }
    if ($PauseMs -gt 0) { Start-Sleep -Milliseconds $PauseMs }
}
Write-Host "Element backfill completed: documents=$processed"

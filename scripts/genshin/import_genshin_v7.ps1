Param(
    [string]$ApiBaseUrl = "http://localhost:9090/api/ragent",
    [string]$Username = "admin",
    [string]$Password = "admin",
    [string]$SourceRoot = "resources/genshin_seed_v6",
    [string]$V7Root = "resources/genshin_seed_v7",
    [string]$KnowledgeBaseName = "genshin_kb_routing_v7",
    [string]$CollectionName = "genshinroutingv7",
    [string]$EmbeddingModel = "text-embedding-v4",
    [int]$MaxDocuments = 0,
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
        # Windows PowerShell otherwise serializes a String body with the active ANSI code page.
        # Send explicit UTF-8 bytes so Chinese Markdown reaches Spring unchanged.
        # -InputObject preserves a one-element chunk array. Piping would unwrap it
        # and make Spring receive an object where the batch endpoint expects an array.
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

function Ensure-KnowledgeBase([string]$Token) {
    $queryName = [uri]::EscapeDataString($KnowledgeBaseName)
    $page = Invoke-RagentJson -Method GET -Path "/knowledge-base?current=1&size=500&name=$queryName" -Token $Token
    foreach ($record in @($page.records)) {
        if ($record.name -eq $KnowledgeBaseName) { return [string]$record.id }
    }
    return [string](Invoke-RagentJson -Method POST -Path "/knowledge-base" -Token $Token -Body @{
        name = $KnowledgeBaseName
        collectionName = $CollectionName
        embeddingModel = $EmbeddingModel
    })
}

function Import-IntentTree([string]$Token, [string]$KbId, [object[]]$Nodes) {
    $existingTree = Invoke-RagentJson -Method GET -Path "/intent-tree/trees" -Token $Token
    $existing = New-Object 'System.Collections.Generic.HashSet[string]'
    function Visit([object]$Node) {
        if ($null -eq $Node) { return }
        if ($Node.intentCode) { [void]$existing.Add([string]$Node.intentCode) }
        foreach ($child in @($Node.children)) { Visit $child }
    }
    foreach ($root in @($existingTree)) { Visit $root }

    $created = 0
    foreach ($node in ($Nodes | Sort-Object level, sortOrder)) {
        if ($existing.Contains([string]$node.intentCode)) { continue }
        [void](Invoke-RagentJson -Method POST -Path "/intent-tree" -Token $Token -Body @{
            kbId = $KbId
            intentCode = [string]$node.intentCode
            name = [string]$node.name
            level = [int]$node.level
            parentCode = $node.parentCode
            description = [string]$node.description
            examples = @($node.examples)
            topK = [int]$node.topK
            kind = [int]$node.kind
            sortOrder = [int]$node.sortOrder
            enabled = [int]$node.enabled
        })
        [void]$existing.Add([string]$node.intentCode)
        $created++
    }
    Write-Host "Semantic intents ready. created=$created total=$($Nodes.Count)"
}

function Upload-Document([string]$Token, [string]$KbId, [System.IO.FileInfo]$File, [string]$SourceDocument) {
    $url = Join-ApiUrl "/knowledge-base/$KbId/docs/upload"
    $raw = & curl.exe -sS -X POST $url `
        -H "Authorization: $Token" `
        -F "file=@$($File.FullName)" `
        -F "sourceType=file" `
        -F "sourceLocation=$SourceDocument" `
        -F "processMode=chunk" `
        -F "chunkStrategy=structure_aware" `
        -F "targetChars=760" `
        -F "maxChars=1100" `
        -F "minChars=340" `
        -F "overlapChars=80"
    if (-not $raw) { throw "Empty upload response: $SourceDocument" }
    $response = $raw | ConvertFrom-Json
    if ($response.code -ne 0 -and $response.code -ne "0") {
        throw "Upload failed: $SourceDocument => [$($response.code)] $($response.message)"
    }
    return [string]$response.data.id
}

function Save-State([string]$Path, [hashtable]$State) {
    $State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Path -Encoding utf8
}

$sourceRootAbs = (Resolve-Path $SourceRoot).Path
$v7RootAbs = (Resolve-Path $V7Root).Path
$manifest = Get-Content -LiteralPath (Join-Path $v7RootAbs "manifest.json") -Raw -Encoding utf8 | ConvertFrom-Json
$nodes = Get-Content -LiteralPath (Join-Path $v7RootAbs "config\intent_nodes.json") -Raw -Encoding utf8 | ConvertFrom-Json
$chunks = Get-Content -LiteralPath (Join-Path $v7RootAbs "artifacts\chunks.jsonl") -Encoding utf8 |
    Where-Object { $_.Trim() } |
    ForEach-Object { $_ | ConvertFrom-Json }
$byDocument = @{}
foreach ($chunk in $chunks) {
    $key = [string]$chunk.metadata.source_document
    if (-not $byDocument.ContainsKey($key)) { $byDocument[$key] = New-Object System.Collections.Generic.List[object] }
    $byDocument[$key].Add($chunk)
}

Write-Host "v7 import plan: documents=$($manifest.totalDocuments), chunks=$($manifest.totalChunks), semanticIntents=$($manifest.semanticIntentNodeCount)"
Write-Host "Target is a NEW KB: $KnowledgeBaseName / $CollectionName"
if (-not $Apply) {
    Write-Host "Dry run only. Re-run with -Apply after the backend has been restarted with the v7 code."
    exit 0
}

$login = Invoke-RagentJson -Method POST -Path "/auth/login" -Body @{ username = $Username; password = $Password }
$token = [string]$login.token
if (-not $token) { throw "Login succeeded but did not return a token" }
$kbId = Ensure-KnowledgeBase $token
Import-IntentTree -Token $token -KbId $kbId -Nodes $nodes

$statePath = Join-Path $v7RootAbs "artifacts\import_state.json"
$state = @{}
if (Test-Path $statePath) {
    # ConvertFrom-Json -AsHashtable is unavailable in Windows PowerShell 5.1.
    $saved = Get-Content -LiteralPath $statePath -Raw -Encoding utf8 | ConvertFrom-Json
    if ($saved) {
        foreach ($property in $saved.PSObject.Properties) {
            $state[$property.Name] = $property.Value
        }
    }
}

$processed = 0
$failed = 0
$selected = 0
foreach ($sourceDocument in ($byDocument.Keys | Sort-Object)) {
    $entry = $state[$sourceDocument]
    if ($entry -and $entry.status -eq "indexed") {
        $processed++
        continue
    }
    if ($MaxDocuments -gt 0 -and $selected -ge $MaxDocuments) { break }
    $selected++

    $sourceFile = Join-Path (Join-Path $sourceRootAbs "docs") ($sourceDocument -replace '/', '\\')
    if (-not (Test-Path $sourceFile)) {
        Write-Warning "Source file missing: $sourceDocument"
        $state[$sourceDocument] = @{ status = "failed"; error = "source file missing" }
        Save-State $statePath $state
        $failed++
        continue
    }

    try {
        $docId = if ($entry -and $entry.docId) { [string]$entry.docId } else { Upload-Document -Token $token -KbId $kbId -File (Get-Item $sourceFile) -SourceDocument $sourceDocument }
        $payload = @($byDocument[$sourceDocument] | ForEach-Object {
            @{ content = $_.content; index = [int]($_.logical_chunk_key -replace '^.*#', ''); metadata = $_.metadata }
        })
        [void](Invoke-RagentJson -Method POST -Path "/knowledge-base/docs/$docId/chunks/batch?write-vector=true" -Token $token -Body $payload)
        $state[$sourceDocument] = @{ status = "indexed"; docId = $docId; chunkCount = $payload.Count; indexedAt = (Get-Date).ToString("o") }
        Save-State $statePath $state
        $processed++
        if ($processed % 25 -eq 0) { Write-Host "Imported $processed documents; failed=$failed" }
        if ($PauseMs -gt 0) { Start-Sleep -Milliseconds $PauseMs }
    } catch {
        $failed++
        # Upload can succeed before embedding/vector indexing fails. Persist the ID so
        # a later retry reuses that document instead of creating a duplicate record.
        $state[$sourceDocument] = @{ status = "failed"; docId = $docId; error = $_.Exception.Message; updatedAt = (Get-Date).ToString("o") }
        Save-State $statePath $state
        Write-Warning "Import failed: $sourceDocument => $($_.Exception.Message)"
    }
}

Write-Host "v7 import finished. kbId=$kbId processed=$processed failed=$failed"

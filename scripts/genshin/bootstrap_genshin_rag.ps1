Param(
    [string]$ApiBaseUrl = "http://localhost:9090/api/ragent",
    [string]$Username = "admin",
    [string]$Password = "admin",
    [string]$SeedRoot = "resources/genshin_seed",
    [string]$KnowledgeBaseName = "genshin_kb_bailian_v4",
    [string]$CollectionName = "genshinbailianv4",
    [string]$EmbeddingModel = "text-embedding-v4",
    [switch]$UploadDocs,
    [int]$MaxUploads = 0,
    [int]$UploadBatchSleepMs = 120,
    [switch]$ChunkAfterUpload,
    [switch]$SkipIntentImport,
    [switch]$SkipMappingImport
)

$ErrorActionPreference = "Stop"

function Join-ApiUrl {
    Param([string]$BaseUrl, [string]$Path)
    return ($BaseUrl.TrimEnd("/") + "/" + $Path.TrimStart("/"))
}

function Invoke-RagentJson {
    Param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null
    )
    $url = Join-ApiUrl -BaseUrl $ApiBaseUrl -Path $Path
    $headers = @{}
    if ($Token) { $headers["Authorization"] = $Token }

    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 80
        $resp = Invoke-RestMethod -Method $Method -Uri $url -Headers $headers -ContentType "application/json" -Body $json
    } else {
        $resp = Invoke-RestMethod -Method $Method -Uri $url -Headers $headers
    }

    if ($resp -is [System.Array]) { return $resp }
    if ($resp.PSObject.Properties.Name -contains "code") {
        if ($resp.code -ne "0" -and $resp.code -ne 0) {
            throw "API $Path failed: [$($resp.code)] $($resp.message)"
        }
        return $resp.data
    }
    return $resp
}

function Get-AllMappings {
    Param([string]$Token)
    $all = @()
    $current = 1
    $size = 500
    while ($true) {
        $path = "/mappings?current=$current&size=$size"
        $pageData = Invoke-RagentJson -Method GET -Path $path -Token $Token
        if ($null -eq $pageData) { break }
        $records = @()
        if ($pageData.PSObject.Properties.Name -contains "records") { $records = $pageData.records }
        if ($records.Count -eq 0) { break }
        $all += $records
        $pages = 1
        if ($pageData.PSObject.Properties.Name -contains "pages") { $pages = [int]$pageData.pages }
        if ($current -ge $pages) { break }
        $current++
    }
    return $all
}

function Get-AllKnowledgeDocNames {
    Param([string]$Token, [string]$KbId)
    $names = New-Object 'System.Collections.Generic.HashSet[string]'
    $pageNo = 1
    $pageSize = 200
    while ($true) {
        $path = "/knowledge-base/$KbId/docs?pageNo=$pageNo&pageSize=$pageSize"
        $pageData = Invoke-RagentJson -Method GET -Path $path -Token $Token
        if ($null -eq $pageData) { break }
        $records = @()
        if ($pageData.PSObject.Properties.Name -contains "records") { $records = $pageData.records }
        if ($records.Count -eq 0) { break }
        foreach ($r in $records) {
            if ($r.PSObject.Properties.Name -contains "docName" -and $r.docName) {
                [void]$names.Add([string]$r.docName)
            }
        }
        $pages = 1
        if ($pageData.PSObject.Properties.Name -contains "pages") { $pages = [int]$pageData.pages }
        if ($pageNo -ge $pages) { break }
        $pageNo++
    }
    return ,$names
}

function Normalize-SeedText {
    Param([string]$Text)
    if ($null -eq $Text) { return $null }
    # 后端当前会在包含中点字符（U+00B7）时返回 B000001，这里统一做兼容规避。
    return $Text.Replace("·", "")
}

function Flatten-IntentCodes {
    Param([object[]]$Trees)
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    function Visit([object]$node) {
        if ($null -eq $node) { return }
        if ($node.PSObject.Properties.Name -contains "intentCode" -and $node.intentCode) {
            [void]$set.Add([string]$node.intentCode)
        }
        if ($node.PSObject.Properties.Name -contains "children" -and $node.children) {
            foreach ($child in $node.children) { Visit $child }
        }
    }
    foreach ($root in $Trees) { Visit $root }
    return ,$set
}

function Ensure-KnowledgeBase {
    Param([string]$Token)
    $nameEncoded = [uri]::EscapeDataString($KnowledgeBaseName)
    $query = Invoke-RagentJson -Method GET -Path "/knowledge-base?current=1&size=500&name=$nameEncoded" -Token $Token
    $records = @()
    if ($query -and $query.PSObject.Properties.Name -contains "records") { $records = $query.records }
    foreach ($kb in $records) {
        if ($kb.name -eq $KnowledgeBaseName) {
            Write-Host "Using existing knowledge base: $($kb.id) / $($kb.name)"
            return [string]$kb.id
        }
    }
    $createdId = Invoke-RagentJson -Method POST -Path "/knowledge-base" -Body @{
        name = $KnowledgeBaseName
        embeddingModel = $EmbeddingModel
        collectionName = $CollectionName
    } -Token $Token
    Write-Host "Created knowledge base: $createdId / $KnowledgeBaseName"
    return [string]$createdId
}

function Ensure-Pipeline {
    Param([string]$Token, [string]$TemplatePath)
    $tpl = Get-Content -Path $TemplatePath -Raw -Encoding UTF8 | ConvertFrom-Json
    $targetName = "genshin_ingest_pipeline"
    $tpl.name = $targetName
    $keyword = [uri]::EscapeDataString($targetName)
    $page = Invoke-RagentJson -Method GET -Path "/ingestion/pipelines?pageNo=1&pageSize=200&keyword=$keyword" -Token $Token
    $records = @()
    if ($page -and $page.PSObject.Properties.Name -contains "records") { $records = $page.records }

    foreach ($item in $records) {
        if ($item.name -eq $targetName) {
            Write-Host "Using existing pipeline: $($item.id) / $($item.name)"
            return [string]$item.id
        }
    }

    $created = Invoke-RagentJson -Method POST -Path "/ingestion/pipelines" -Body @{
        name = $tpl.name
        description = $tpl.description
        nodes = $tpl.nodes
    } -Token $Token
    Write-Host "Created pipeline: $($created.id) / $($created.name)"
    return [string]$created.id
}

function Import-IntentNodes {
    Param([string]$Token, [string]$KbId, [string]$IntentPath)
    $trees = Invoke-RagentJson -Method GET -Path "/intent-tree/trees" -Token $Token
    $existingRaw = Flatten-IntentCodes -Trees $trees
    if ($existingRaw -is [System.Collections.Generic.HashSet[string]]) {
        $existing = $existingRaw
    } else {
        $existing = New-Object 'System.Collections.Generic.HashSet[string]'
        foreach ($code in @($existingRaw)) {
            if ($code) { [void]$existing.Add([string]$code) }
        }
    }
    $nodes = Get-Content -Path $IntentPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $ordered = $nodes | Sort-Object level, sortOrder
    $created = 0
    $skipped = 0
    $failed = 0
    foreach ($node in $ordered) {
        $code = [string]$node.intentCode
        if ($existing.Contains($code)) {
            $skipped++
            continue
        }
        $nameVal = Normalize-SeedText -Text ([string]$node.name)
        if ($nameVal.Length -gt 60) {
            $nameVal = $nameVal.Substring(0, 60)
        }
        $descVal = Normalize-SeedText -Text ([string]$node.description)
        if ($descVal.Length -gt 500) {
            $descVal = $descVal.Substring(0, 500)
        }
        $examplesVal = @()
        foreach ($ex in @($node.examples)) {
            $examplesVal += (Normalize-SeedText -Text ([string]$ex))
        }
        $body = @{
            kbId = [string]$KbId
            intentCode = $code
            name = $nameVal
            level = [int]$node.level
            parentCode = $node.parentCode
            description = $descVal
            examples = $examplesVal
            topK = $node.topK
            kind = $node.kind
            sortOrder = $node.sortOrder
            enabled = $node.enabled
        }
        try {
            [void](Invoke-RagentJson -Method POST -Path "/intent-tree" -Body $body -Token $Token)
            [void]$existing.Add($code)
            $created++
        } catch {
            $failed++
            Write-Warning "Intent create failed: code=$code name=$nameVal error=$($_.Exception.Message)"
            continue
        }
    }
    Write-Host "Intent import done. created=$created skipped=$skipped failed=$failed"
}

function Import-QueryMappings {
    Param([string]$Token, [string]$MappingPath)
    $allExisting = Get-AllMappings -Token $Token
    $existingSet = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($item in $allExisting) {
        $key = ([string]$item.sourceTerm).ToLowerInvariant() + "|" + [string]$item.targetTerm
        [void]$existingSet.Add($key)
    }

    $seedMappings = Get-Content -Path $MappingPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $created = 0
    $skipped = 0
    $failed = 0
    foreach ($m in $seedMappings) {
        $source = Normalize-SeedText -Text ([string]$m.sourceTerm)
        $target = Normalize-SeedText -Text ([string]$m.targetTerm)
        if ($source.Length -gt 120) { $source = $source.Substring(0, 120) }
        if ($target.Length -gt 120) { $target = $target.Substring(0, 120) }
        if ([string]::IsNullOrWhiteSpace($source) -or [string]::IsNullOrWhiteSpace($target) -or $source -eq $target) {
            $skipped++
            continue
        }
        $key = $source.ToLowerInvariant() + "|" + $target
        if ($existingSet.Contains($key)) {
            $skipped++
            continue
        }
        try {
            [void](Invoke-RagentJson -Method POST -Path "/mappings" -Body @{
                sourceTerm = $source
                targetTerm = $target
                matchType = $m.matchType
                priority = $m.priority
                enabled = $m.enabled
                remark = (Normalize-SeedText -Text ([string]$m.remark))
            } -Token $Token)
            [void]$existingSet.Add($key)
            $created++
        } catch {
            $failed++
            Write-Warning "Mapping create failed: source=$source target=$target error=$($_.Exception.Message)"
            continue
        }
    }
    Write-Host "Mapping import done. created=$created skipped=$skipped failed=$failed"
}

function Upload-MarkdownDocs {
    Param([string]$Token, [string]$KbId, [string]$PipelineId, [string]$DocsDir)
    $files = Get-ChildItem -Path $DocsDir -Recurse -File -Filter *.md | Sort-Object FullName
    if ($files.Count -eq 0) {
        Write-Host "No markdown docs found under: $DocsDir"
        return
    }
    $existing = Get-AllKnowledgeDocNames -Token $Token -KbId $KbId
    if ($null -eq $existing) {
        $existing = New-Object 'System.Collections.Generic.HashSet[string]'
    }

    $uploaded = 0
    $skipped = 0
    $failed = 0
    $limit = $MaxUploads
    $uploadUrl = Join-ApiUrl -BaseUrl $ApiBaseUrl -Path "/knowledge-base/$KbId/docs/upload"

    foreach ($file in $files) {
        if ($limit -gt 0 -and $uploaded -ge $limit) { break }
        if ($existing.Contains($file.Name)) {
            $skipped++
            continue
        }
        $resultRaw = & curl.exe -sS -X POST $uploadUrl `
            -H "Authorization: $Token" `
            -F "file=@$($file.FullName)" `
            -F "sourceType=file" `
            -F "processMode=pipeline" `
            -F "pipelineId=$PipelineId"
        if (-not $resultRaw) {
            Write-Warning "Upload failed (empty): $($file.FullName)"
            $failed++
            continue
        }

        try {
            $obj = $resultRaw | ConvertFrom-Json
            if ($obj.code -ne "0" -and $obj.code -ne 0) {
                Write-Warning "Upload failed: $($file.Name) => [$($obj.code)] $($obj.message)"
                $failed++
                continue
            }
            $docId = [string]$obj.data.id
            [void]$existing.Add($file.Name)
            $uploaded++
            if ($ChunkAfterUpload -and $docId) {
                [void](Invoke-RagentJson -Method POST -Path "/knowledge-base/docs/$docId/chunk" -Token $Token)
            }
        } catch {
            Write-Warning "Upload parse failed: $($file.Name) => $($_.Exception.Message)"
            $failed++
        }

        if ($UploadBatchSleepMs -gt 0) { Start-Sleep -Milliseconds $UploadBatchSleepMs }
    }

    Write-Host "Doc upload done. uploaded=$uploaded skipped=$skipped failed=$failed totalFound=$($files.Count)"
}

$seedRootAbs = (Resolve-Path $SeedRoot).Path
$configDir = Join-Path $seedRootAbs "config"
$docsDir = Join-Path $seedRootAbs "docs"

$intentFile = Join-Path $configDir "intent_nodes.json"
$mappingFile = Join-Path $configDir "query_term_mappings.json"
$pipelineFile = Join-Path $configDir "pipeline_template.json"

if (-not (Test-Path $intentFile)) { throw "Missing file: $intentFile" }
if (-not (Test-Path $mappingFile)) { throw "Missing file: $mappingFile" }
if (-not (Test-Path $pipelineFile)) { throw "Missing file: $pipelineFile" }

Write-Host "Login with user: $Username"
$login = Invoke-RagentJson -Method POST -Path "/auth/login" -Body @{ username = $Username; password = $Password }
$token = [string]$login.token
if (-not $token) { throw "Login succeeded but token is empty." }
Write-Host "Login success."

$kbId = Ensure-KnowledgeBase -Token $token
$pipelineId = Ensure-Pipeline -Token $token -TemplatePath $pipelineFile
if ($SkipIntentImport) {
    Write-Host "Skip intent import."
} else {
    Import-IntentNodes -Token $token -KbId $kbId -IntentPath $intentFile
}
if ($SkipMappingImport) {
    Write-Host "Skip query mappings import."
} else {
    Import-QueryMappings -Token $token -MappingPath $mappingFile
}

if ($UploadDocs) {
    Write-Host "Upload docs from: $docsDir"
    Upload-MarkdownDocs -Token $token -KbId $kbId -PipelineId $pipelineId -DocsDir $docsDir
} else {
    Write-Host "Skip docs upload. Use -UploadDocs to enable."
}

Write-Host "Bootstrap complete. kbId=$kbId pipelineId=$pipelineId"

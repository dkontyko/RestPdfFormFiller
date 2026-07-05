// ---------------------------------------------------------------------------
// diagnostics.bicep — Routes platform logs + metrics from every RG resource
// that supports Azure Monitor diagnostic settings into the Log Analytics
// workspace (PdfFormFillerLogs).
//
// Policy (for now): send EVERYTHING — `allLogs` category group + all metrics.
// This is intentionally broad so we can see what's useful; trim categories
// later once the signal is understood (watch the workspace daily-cap in
// monitoring.bicep — currently 1 GB).
//
// Resources covered:
//   - Function App (production site) + deployment slot  -> allLogs + AllMetrics
//   - Storage blob/file/queue/table services            -> allLogs + Transaction
//   - Storage account (root)                            -> Transaction + Capacity metrics
//
// Resources intentionally NOT covered (no diagnosticSettings support):
//   - App Service plan (Microsoft.Web/serverfarms)
//   - Application Insights component (it IS a telemetry sink)
//   - User-assigned / system-assigned managed identities
//   - Role assignments
// The Log Analytics workspace itself can emit audit logs, but self-routing is
// omitted here to avoid a recursive ingestion loop.
// ---------------------------------------------------------------------------

@description('Resource ID of the Log Analytics workspace that receives everything.')
param workspaceId string

@description('Storage account name whose services get access logs routed.')
param storageAccountName string

@description('Function App (production site) name.')
param functionAppName string

@description('Deployment slot name.')
param slotName string

var diagName = 'to-law'

// ---- Existing resources (created by the other modules) --------------------

resource functionApp 'Microsoft.Web/sites@2023-12-01' existing = {
  name: functionAppName

  resource slot 'slots' existing = {
    name: slotName
  }
}

resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' existing = {
  name: storageAccountName

  resource blobService 'blobServices' existing = {
    name: 'default'
  }
  resource fileService 'fileServices' existing = {
    name: 'default'
  }
  resource queueService 'queueServices' existing = {
    name: 'default'
  }
  resource tableService 'tableServices' existing = {
    name: 'default'
  }
}

// ---- Function App: production site + slot ---------------------------------

resource siteDiagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: diagName
  scope: functionApp
  properties: {
    workspaceId: workspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
    metrics: [
      {
        category: 'AllMetrics'
        enabled: true
      }
    ]
  }
}

resource slotDiagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: diagName
  scope: functionApp::slot
  properties: {
    workspaceId: workspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
    metrics: [
      {
        category: 'AllMetrics'
        enabled: true
      }
    ]
  }
}

// ---- Storage account root (metrics only; no logs at account scope) ---------

resource storageDiagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: diagName
  scope: storage
  properties: {
    workspaceId: workspaceId
    metrics: [
      {
        category: 'Transaction'
        enabled: true
      }
      {
        category: 'Capacity'
        enabled: true
      }
    ]
  }
}

// ---- Storage services: blob / file / queue / table (access logs) ----------

resource blobDiagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: diagName
  scope: storage::blobService
  properties: {
    workspaceId: workspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
    metrics: [
      {
        category: 'Transaction'
        enabled: true
      }
    ]
  }
}

resource fileDiagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: diagName
  scope: storage::fileService
  properties: {
    workspaceId: workspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
    metrics: [
      {
        category: 'Transaction'
        enabled: true
      }
    ]
  }
}

resource queueDiagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: diagName
  scope: storage::queueService
  properties: {
    workspaceId: workspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
    metrics: [
      {
        category: 'Transaction'
        enabled: true
      }
    ]
  }
}

resource tableDiagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: diagName
  scope: storage::tableService
  properties: {
    workspaceId: workspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
    metrics: [
      {
        category: 'Transaction'
        enabled: true
      }
    ]
  }
}

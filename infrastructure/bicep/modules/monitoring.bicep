// ---------------------------------------------------------------------------
// monitoring.bicep — Log Analytics workspace + workspace-based Application
// Insights, plus the auto-created Failure Anomalies smart-detector alert rule
// and its Smart Detection action group (captured here so the Bicep is a
// COMPLETE record of the intended resource group).
//
// Decisions encoded:
//   - Everything in centralus (the old westus2 workspace is retired — see README).
//   - Retention = 31 days (the included/free floor; no extra retention cost).
//   - Daily ingestion cap = 1 GB (runaway guardrail).
//   - Entra-only ingestion (DisableLocalAuth): telemetry authenticates with the
//     Function App's managed identity, not an instrumentation key.
// ---------------------------------------------------------------------------

@description('Azure region for the monitoring resources.')
param location string

@description('Log Analytics workspace name.')
param workspaceName string

@description('Application Insights component name.')
param appInsightsName string

@description('Retention in days for the Log Analytics workspace. Workspace-based App Insights inherits this; the component-level retention is not set.')
@minValue(30)
@maxValue(730)
param retentionInDays int = 31

@description('Daily ingestion cap in GB. Use -1 for no cap.')
param dailyQuotaGb int = 1

@description('Enforce Entra-only (managed-identity) ingestion by disabling local/key auth on App Insights. Stage this: deploy false first, verify telemetry, then set true.')
param enforceEntraOnlyIngestion bool = true

@description('Tags applied to the monitoring resources.')
param tags object = {}

resource workspace 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: workspaceName
  location: location
  tags: tags
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: retentionInDays
    workspaceCapping: {
      dailyQuotaGb: dailyQuotaGb
    }
    features: {
      enableLogAccessUsingOnlyResourcePermissions: true
    }
  }
}

resource appInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: appInsightsName
  location: location
  kind: 'web'
  tags: tags
  properties: {
    Application_Type: 'web'
    // Workspace-based (classic App Insights is retired).
    WorkspaceResourceId: workspace.id
    IngestionMode: 'LogAnalytics'
    DisableLocalAuth: enforceEntraOnlyIngestion
    // NOTE: RetentionInDays is intentionally omitted. For a workspace-based
    // component it is ignored (retention is governed by the Log Analytics
    // workspace above), and the component only accepts a fixed set of values
    // (30, 60, 90, ...) — passing 31 fails with "Must use an allowed retention
    // setting."
    publicNetworkAccessForIngestion: 'Enabled'
    publicNetworkAccessForQuery: 'Enabled'
  }
}

// Smart Detection action group — notifies subscription monitoring roles. This
// is the resource Azure auto-creates as "Application Insights Smart Detection".
resource smartDetectionActionGroup 'Microsoft.Insights/actionGroups@2023-01-01' = {
  name: 'Application Insights Smart Detection'
  location: 'Global'
  tags: tags
  properties: {
    groupShortName: 'SmartDetect'
    enabled: true
    armRoleReceivers: [
      {
        name: 'Monitoring Contributor'
        roleId: '749f88d5-cbae-40b8-bcfc-e573ddc772fa'
        useCommonAlertSchema: true
      }
      {
        name: 'Monitoring Reader'
        roleId: '43d0d8ad-25c7-4714-9337-8ba259a9fe05'
        useCommonAlertSchema: true
      }
    ]
  }
}

// Failure Anomalies smart-detector alert rule (auto-created with App Insights).
resource failureAnomalies 'Microsoft.AlertsManagement/smartDetectorAlertRules@2021-04-01' = {
  name: 'Failure Anomalies - ${appInsightsName}'
  location: 'global'
  tags: tags
  properties: {
    description: 'Failure Anomalies notifies you of an unusual rise in the rate of failed HTTP requests or dependency calls.'
    state: 'Enabled'
    severity: 'Sev3'
    frequency: 'PT1M'
    detector: {
      id: 'FailureAnomaliesDetector'
    }
    scope: [
      appInsights.id
    ]
    actionGroups: {
      groupIds: [
        smartDetectionActionGroup.id
      ]
    }
  }
}

output workspaceId string = workspace.id
output appInsightsId string = appInsights.id
output appInsightsName string = appInsights.name
output appInsightsConnectionString string = appInsights.properties.ConnectionString

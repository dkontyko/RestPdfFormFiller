// ---------------------------------------------------------------------------
// main.bicep — Resource-group-scoped orchestrator for the RestPdfFormFiller
// infrastructure. Intended to be a COMPLETE, faithful record of resource group
// `PdfFormFiller`. Validate with `az deployment group what-if` before applying
// (see scripts/deploy.ps1 and README.md).
// ---------------------------------------------------------------------------

targetScope = 'resourceGroup'

@description('Location for all resources.')
param location string = 'centralus'

@description('Storage account name (Function App backing store).')
param storageAccountName string

@description('Log Analytics workspace name.')
param workspaceName string

@description('Application Insights component name.')
param appInsightsName string

@description('Consumption plan name.')
param planName string

@description('Function App name.')
param functionAppName string

@description('Deployment slot name.')
param slotName string = 'dev'

@description('Production content share name (must match existing).')
param prodContentShareName string

@description('Deployment-slot content share name (must match existing).')
param devContentShareName string

@description('Name of the UAMI that deploys the Function App.')
param functionDeployIdentityName string

@description('Name of the UAMI that deploys the Power Platform connector.')
param connectorDeployIdentityName string

@description('GitHub repository in owner/name form.')
param githubRepo string

@description('GitHub environment that gates the Function App deploy.')
param functionDeployEnvironment string = 'azure-dev'

@description('GitHub environment that gates the connector deploy.')
param connectorDeployEnvironment string = 'power-platform-dev'

@description('Workspace / App Insights retention in days.')
param retentionInDays int = 31

@description('Workspace daily ingestion cap in GB (-1 = no cap).')
param dailyQuotaGb int = 1

@description('Enforce Entra-only (managed-identity) App Insights ingestion by disabling key-based auth. Default true (steady state); set false only to temporarily re-enable key-based ingestion while verifying MI telemetry.')
param enforceEntraOnlyIngestion bool = true

@description('Tags applied to all resources.')
param tags object = {}

module identities 'modules/identities.bicep' = {
  name: 'identities'
  params: {
    location: location
    functionDeployIdentityName: functionDeployIdentityName
    connectorDeployIdentityName: connectorDeployIdentityName
    githubRepo: githubRepo
    functionDeployEnvironment: functionDeployEnvironment
    connectorDeployEnvironment: connectorDeployEnvironment
    tags: tags
  }
}

module storage 'modules/storage.bicep' = {
  name: 'storage'
  params: {
    location: location
    storageAccountName: storageAccountName
    tags: tags
  }
}

module monitoring 'modules/monitoring.bicep' = {
  name: 'monitoring'
  params: {
    location: location
    workspaceName: workspaceName
    appInsightsName: appInsightsName
    retentionInDays: retentionInDays
    dailyQuotaGb: dailyQuotaGb
    enforceEntraOnlyIngestion: enforceEntraOnlyIngestion
    tags: tags
  }
}

module functionApp 'modules/functionApp.bicep' = {
  name: 'functionApp'
  params: {
    location: location
    planName: planName
    functionAppName: functionAppName
    slotName: slotName
    storageAccountName: storageAccountName
    appInsightsConnectionString: monitoring.outputs.appInsightsConnectionString
    prodContentShareName: prodContentShareName
    devContentShareName: devContentShareName
    tags: tags
  }
  dependsOn: [
    storage
  ]
}

module roleAssignments 'modules/roleAssignments.bicep' = {
  name: 'roleAssignments'
  params: {
    storageAccountName: storageAccountName
    appInsightsName: appInsightsName
    functionAppName: functionAppName
    functionDeployPrincipalId: identities.outputs.functionDeployPrincipalId
    sitePrincipalId: functionApp.outputs.sitePrincipalId
    slotPrincipalId: functionApp.outputs.slotPrincipalId
  }
}

// Route all platform logs + metrics from the RG's resources into the workspace.
module diagnostics 'modules/diagnostics.bicep' = {
  name: 'diagnostics'
  params: {
    workspaceId: monitoring.outputs.workspaceId
    storageAccountName: storageAccountName
    functionAppName: functionAppName
    slotName: slotName
  }
  dependsOn: [
    storage
    functionApp
  ]
}

// Outputs consumed by scripts/configure-repo.ps1.
output functionAppName string = functionApp.outputs.functionAppName
output functionAppHostName string = functionApp.outputs.defaultHostName
output functionDeployClientId string = identities.outputs.functionDeployClientId
output connectorDeployClientId string = identities.outputs.connectorDeployClientId
output subscriptionId string = subscription().subscriptionId
output tenantId string = tenant().tenantId

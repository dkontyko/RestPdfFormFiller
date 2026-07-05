// ---------------------------------------------------------------------------
// roleAssignments.bicep — Least-privilege RBAC for the deploy identity and the
// Function App runtime identities.
//
//   - functionDeploy UAMI  -> Website Contributor on the Function App
//     (so GitHub Actions can deploy code).
//   - site + slot SAMI     -> Storage Blob Data Contributor + Queue Data Contributor
//     + Table Data Contributor on the storage account (keyless AzureWebJobsStorage).
//   - site + slot SAMI     -> Monitoring Metrics Publisher on App Insights
//     (Entra-authenticated telemetry ingestion).
//
// The connector UAMI intentionally gets NO Azure role — its access is via a
// Dataverse application user (scripts/configure-dataverse.ps1).
// ---------------------------------------------------------------------------

@description('Existing storage account name.')
param storageAccountName string

@description('Existing Application Insights component name.')
param appInsightsName string

@description('Existing Function App name.')
param functionAppName string

@description('Principal ID of the GitHub function-deploy UAMI.')
param functionDeployPrincipalId string

@description('Principal ID of the Function App (production site) system-assigned identity.')
param sitePrincipalId string

@description('Principal ID of the deployment slot system-assigned identity.')
param slotPrincipalId string

// Built-in role definition IDs.
var websiteContributor = 'de139f84-1756-47ae-9be6-808fbbe84772'
var storageBlobDataContributor = 'ba92f5b4-2d11-453d-a403-e96b0029c9fe'
var storageQueueDataContributor = '974c5e8b-45b9-4653-ba55-5f855dd0fb88'
var storageTableDataContributor = '0a9a7e1f-b9d0-4cc4-a60d-0319b160aaa3'
var monitoringMetricsPublisher = '3913510d-42f4-4e42-8a64-420c390055eb'

var runtimePrincipals = [
  sitePrincipalId
  slotPrincipalId
]
var storageRoleIds = [
  storageBlobDataContributor
  storageQueueDataContributor
  storageTableDataContributor
]
// Cartesian product of {site,slot} x {blob,queue,table} storage roles.
var storageAssignments = flatten(map(runtimePrincipals, principal => map(storageRoleIds, roleId => {
  principalId: principal
  roleId: roleId
})))

resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' existing = {
  name: storageAccountName
}

resource appInsights 'Microsoft.Insights/components@2020-02-02' existing = {
  name: appInsightsName
}

resource functionApp 'Microsoft.Web/sites@2023-12-01' existing = {
  name: functionAppName
}

// GitHub function-deploy UAMI -> Website Contributor on the Function App.
resource deployRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(functionApp.id, functionDeployPrincipalId, websiteContributor)
  scope: functionApp
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', websiteContributor)
    principalId: functionDeployPrincipalId
    principalType: 'ServicePrincipal'
  }
}

// site + slot SAMI -> storage data roles.
resource storageRoleAssignments 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for a in storageAssignments: {
  name: guid(storage.id, a.principalId, a.roleId)
  scope: storage
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', a.roleId)
    principalId: a.principalId
    principalType: 'ServicePrincipal'
  }
}]

// site + slot SAMI -> Monitoring Metrics Publisher on App Insights.
resource aiRoleAssignments 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for p in runtimePrincipals: {
  name: guid(appInsights.id, p, monitoringMetricsPublisher)
  scope: appInsights
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', monitoringMetricsPublisher)
    principalId: p
    principalType: 'ServicePrincipal'
  }
}]

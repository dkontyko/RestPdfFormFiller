// ---------------------------------------------------------------------------
// functionApp.bicep — Windows Y1 (Consumption) plan + Java Function App + its
// `dev` deployment slot, with a system-assigned identity on BOTH site and slot.
//
// App settings are declared authoritatively (this REPLACES whatever is live),
// which is how the stale/debug settings are removed. Dropped vs. the current
// live config:
//   APPINSIGHTS_INSTRUMENTATIONKEY        (legacy — replaced by connection string)
//   MICROSOFT_PROVIDER_AUTHENTICATION_SECRET (Easy Auth is disabled)
//   clientId / clientSecret / tenantId / WebUrl (stale OBO/Graph leftovers)
//   JAVA_OPTS / HTTP_PLATFORM_DEBUG_PORT  (remote-debug artifacts on the slot)
//   WEBSITE_HTTPLOGGING_RETENTION_DAYS    (slot-only drift)
//   WEBSITE_RUN_FROM_PACKAGE              (deployment-managed; re-added by deploy)
//
// Storage auth: AzureWebJobsStorage uses identity-based (keyless) connection via
// the site/slot SAMI. The content share (WEBSITE_CONTENT*) still needs a
// key-based connection on Y1 — supplied here via listKeys(), never hardcoded.
// ---------------------------------------------------------------------------

@description('Azure region.')
param location string

@description('App Service (Consumption) plan name.')
param planName string

@description('Function App name.')
param functionAppName string

@description('Deployment slot name.')
param slotName string = 'dev'

@description('Name of the existing/created storage account (referenced for content-share keys).')
param storageAccountName string

@description('Application Insights connection string (from the monitoring module).')
param appInsightsConnectionString string

@description('Content share name for the production site (must match the existing share to avoid churn).')
param prodContentShareName string

@description('Content share name for the deployment slot (must match the existing share to avoid churn).')
param devContentShareName string

@description('App Insights cloud role name for production telemetry.')
param prodRoleName string = functionAppName

@description('App Insights cloud role name for slot telemetry (distinguishes dev from prod in the shared component).')
param devRoleName string = '${functionAppName}-${slotName}'

@description('Tags applied to the plan, site and slot.')
param tags object = {}

// Existing storage account — used only to build the content-share connection
// string via listKeys() at deploy time (the key is never written to source).
resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' existing = {
  name: storageAccountName
}

var contentShareConnectionString = 'DefaultEndpointsProtocol=https;AccountName=${storage.name};AccountKey=${storage.listKeys().keys[0].value};EndpointSuffix=${environment().suffixes.storage}'

// Settings identical across site and slot.
var sharedAppSettings = [
  {
    name: 'FUNCTIONS_EXTENSION_VERSION'
    value: '~4'
  }
  {
    name: 'FUNCTIONS_WORKER_RUNTIME'
    value: 'java'
  }
  // Identity-based (keyless) AzureWebJobsStorage — uses the site/slot SAMI.
  {
    name: 'AzureWebJobsStorage__accountName'
    value: storage.name
  }
  {
    name: 'AzureWebJobsSecretStorageType'
    value: 'Blob'
  }
  {
    name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
    value: appInsightsConnectionString
  }
  // Authenticate telemetry ingestion with the managed identity (no key).
  {
    name: 'APPLICATIONINSIGHTS_AUTHENTICATION_STRING'
    value: 'Authorization=AAD'
  }
  {
    name: 'APPLICATIONINSIGHTS_ENABLE_AGENT'
    value: 'true'
  }
  // Content share stays key-based on Y1 (see module header).
  {
    name: 'WEBSITE_CONTENTAZUREFILECONNECTIONSTRING'
    value: contentShareConnectionString
  }
]

var prodAppSettings = concat(sharedAppSettings, [
  {
    name: 'WEBSITE_CONTENTSHARE'
    value: prodContentShareName
  }
  {
    name: 'APPLICATIONINSIGHTS_ROLE_NAME'
    value: prodRoleName
  }
])

var devAppSettings = concat(sharedAppSettings, [
  {
    name: 'WEBSITE_CONTENTSHARE'
    value: devContentShareName
  }
  {
    name: 'APPLICATIONINSIGHTS_ROLE_NAME'
    value: devRoleName
  }
])

resource plan 'Microsoft.Web/serverfarms@2023-12-01' = {
  name: planName
  location: location
  kind: 'functionapp'
  sku: {
    name: 'Y1'
    tier: 'Dynamic'
  }
  properties: {}
}

resource functionApp 'Microsoft.Web/sites@2023-12-01' = {
  name: functionAppName
  location: location
  kind: 'functionapp'
  tags: tags
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    serverFarmId: plan.id
    httpsOnly: true
    clientCertMode: 'Required'
    siteConfig: {
      minTlsVersion: '1.2'
      ftpsState: 'Disabled'
      // 64-bit worker (recommended for Java; the live app was 32-bit, which
      // this deliberately changes). netFrameworkVersion is intentionally unset:
      // it only affects .NET workloads and has no bearing on the Java worker.
      use32BitWorkerProcess: false
      javaVersion: '25'
      appSettings: prodAppSettings
    }
  }
}

resource slot 'Microsoft.Web/sites/slots@2023-12-01' = {
  parent: functionApp
  name: slotName
  location: location
  kind: 'functionapp'
  tags: tags
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    serverFarmId: plan.id
    httpsOnly: true
    clientCertMode: 'Required'
    siteConfig: {
      minTlsVersion: '1.2'
      ftpsState: 'Disabled'
      use32BitWorkerProcess: false
      javaVersion: '25'
      appSettings: devAppSettings
    }
  }
}

// Mark the telemetry role name as slot-sticky so a swap keeps each slot's
// Application Insights identity in place (production stays 'RestPdfFormFiller',
// the slot stays 'RestPdfFormFiller-dev'). The other candidates don't belong here:
//   - WEBSITE_CONTENTSHARE / WEBSITE_CONTENTAZUREFILECONNECTIONSTRING are
//     platform-managed on Consumption and Azure rejects them as slot settings.
//   - APPLICATIONINSIGHTS_CONNECTION_STRING / _AUTHENTICATION_STRING are
//     identical across both slots, so stickiness would be a no-op.
resource slotConfigNames 'Microsoft.Web/sites/config@2023-12-01' = {
  parent: functionApp
  name: 'slotConfigNames'
  properties: {
    appSettingNames: [
      'APPLICATIONINSIGHTS_ROLE_NAME'
    ]
  }
}

output functionAppId string = functionApp.id
output functionAppName string = functionApp.name
output defaultHostName string = functionApp.properties.defaultHostName
output sitePrincipalId string = functionApp.identity.principalId
output slotPrincipalId string = slot.identity.principalId

// ---------------------------------------------------------------------------
// storage.bicep — Storage account backing the Azure Function App.
//
// This account is REQUIRED by the Functions runtime even though the app code
// never touches blobs directly. It holds:
//   - blob container `azure-webjobs-hosts`   (host singleton / leader locks)
//   - blob container `azure-webjobs-secrets` (the function keys / x-functions-key)
//   - tables `AzureFunctionsDiagnosticEvents*`(host diagnostic events)
//   - the Azure Files content share (WEBSITE_CONTENTSHARE) the app runs from
// ---------------------------------------------------------------------------

@description('Azure region for the storage account.')
param location string

@description('Name of the storage account backing the Function App.')
param storageAccountName string

@description('Tags applied to the storage account.')
param tags object = {}

resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: storageAccountName
  location: location
  tags: tags
  sku: {
    name: 'Standard_LRS'
  }
  kind: 'StorageV2'
  properties: {
    accessTier: 'Hot'
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    allowBlobPublicAccess: false
    // Shared-key access must stay ENABLED: on the Y1 Consumption plan the
    // content share (WEBSITE_CONTENTAZUREFILECONNECTIONSTRING) still requires a
    // key-based connection. AzureWebJobsStorage itself uses identity-based
    // (keyless) auth — see functionApp.bicep + roleAssignments.bicep.
    allowSharedKeyAccess: true
    publicNetworkAccess: 'Enabled'
  }
}

output id string = storage.id
output name string = storage.name

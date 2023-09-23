Migrated from https://github.com/dkontyko/PdfFormFillerConnector.

The accompanying Power Automate custom connector for the Function app.

Write-up note: To configure OAuth with AAD, you need to create the app registration, then you need to go to Expose an API and add "User.Read" as a scope after your application URI. Then you need to wait a day for AAD to catch up to your chanage, then go (back) to API permissions and click Add a permission, click My APIs, then select your newly created User.Read permission and click Add permissions.
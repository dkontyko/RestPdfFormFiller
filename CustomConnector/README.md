# RestPdfFormFiller Power Automate connector

This custom connector reads and fills XFA PDF forms through the Function App.
Its read actions normalize PDF input to `application/octet-stream`, so a flow
can pass SharePoint or OneDrive **File Content** directly without a conversion
expression.

## Authentication

The checked-in connector definition uses an Azure Functions access key in the
`x-functions-key` header. When creating a connector connection, provide an
appropriate Function App key:

- Use a function key when the connection needs access to one endpoint only.
- Use a host key when one connection must call all three connector actions.
- Do not use the `_master` key, which also authorizes administrative runtime
  operations.

Azure Functions documents the scopes, retrieval, and rotation of
[function access keys](https://learn.microsoft.com/azure/azure-functions/function-keys-how-to).
After rotating the chosen key, update or recreate affected connector
connections. OAuth is not configured in this connector definition.

## Recommended flow

1. Add SharePoint or OneDrive **Get file content** for the source XFA PDF.
2. Optionally use **Get XFA Form Data** or **Get XFA Form Schema** to inspect
   the form. Pass the action's **File Content** value directly as the PDF
   input. The connector's request policy sets `Content-Type` to
   `application/octet-stream` before it sends the request.
3. Add **Fill XFA Form Data**:
   - Pass the same **File Content** value as the source PDF.
   - Provide `formData` as an object containing only its `data` object.
   - Use `writeMode: patch` for targeted updates or `writeMode: put` to replace
     the form data and clear omitted fields.
   - With `patch`, choose `patchMode` as `overwrite`, `ifEmpty`, or
     `failOnConflict`. Do not provide `patchMode` with `put`.
   - Use `validateOnly: true` to validate the request and source form without
     emitting a filled PDF.
4. Add SharePoint or OneDrive **Create file** and pass it the PDF response from
   **Fill XFA Form Data** directly.

The read-input policy is intentionally limited to the two read actions.
`FillXfaData` sends JSON containing the source PDF as a Base64 field, so it does
not use the raw-PDF request path. For details about the connector policy model,
see Microsoft's [Set HTTP Header](https://learn.microsoft.com/connectors/custom-connectors/policy-templates/setheader/setheader)
reference.

## Expected fill behavior

- `patch` preserves fields omitted from `formData`; `put` clears them.
- `failOnConflict` returns HTTP 409 when a supplied value would overwrite a
  different, non-empty value.
- A successful non-validation request returns raw `application/pdf` content.
- Invalid request contracts or source PDFs return HTTP 400.

# RestPdfFormFiller

[![Azure Function App Deployment](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/main_restpdfformfiller(dev).yml/badge.svg)](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/main_restpdfformfiller(dev).yml)
[![CodeQL](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/codeql.yml/badge.svg)](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/codeql.yml)
[![Java CI with Maven](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/maven.yml/badge.svg)](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/maven.yml)

RestPdfFormFiller reads XFA PDF form data, generates a simple schema for it,
and writes supplied values back to an XFA PDF. The accompanying Power Automate
custom connector supports direct SharePoint or OneDrive **File Content** input
and returns filled documents as PDF files.

## Function App structure

- `HttpTriggerFunctions` exposes the HTTP endpoints and creates responses.
- `RestPdfApi` performs the XFA and PDF operations.

## Endpoints

### GetXfaData — `POST /api/GetXfaData`

Returns the XFA datasets node from an XFA PDF.

- Send the PDF as the raw request body.
- Set the required `format` query parameter to `json` or `xml`.
- The response is the requested JSON or XML representation of the form data.

### GetXfaSchema — `POST /api/GetXfaSchema`

Returns a basic JSON schema describing the datasets in an XFA PDF.

- Send the PDF as the raw request body.
- The response contains object and string field types suitable for the custom
  connector's dynamic-schema use case.

### FillXfaData — `POST /api/FillXfaData`

Writes data into an XFA PDF and returns the resulting document as raw
`application/pdf` bytes. The request is JSON with these fields:

| Field | Required | Behavior |
| --- | --- | --- |
| `templateBase64` | Yes | Base64-encoded source XFA PDF. In Power Automate, pass **File Content** directly; Power Automate encodes it automatically for this `format: byte` field. |
| `formData` | Yes | Object containing only a `data` object whose shape matches the form data. |
| `writeMode` | No | `patch` (default) updates supplied fields and preserves omitted ones. `put` replaces the form data and clears omitted fields. |
| `patchMode` | No | With `patch`, choose `overwrite` (default), `ifEmpty`, or `failOnConflict`. Do not supply it with `put`. |
| `validateOnly` | No | When `true`, validates the request and source form without returning a filled PDF. Defaults to `false`. |

For example, a patch request has this shape:

```json
{
  "templateBase64": "<base64-encoded PDF>",
  "formData": {
    "data": {
      "form1": {
        "Page1": {
          "SSN": "999-99-9999"
        }
      }
    }
  },
  "writeMode": "patch",
  "patchMode": "overwrite"
}
```

`failOnConflict` returns HTTP 409 if a supplied value would replace a different,
non-empty existing value. Invalid request contracts or non-XFA source PDFs
return HTTP 400.

### Function routes and connector action IDs

The Function App route names and custom-connector operation IDs are separate
identifiers. The connector keeps its existing `GetXfaFormSchema` operation ID
for compatibility, while it calls the `GetXfaSchema` Function App route.

| Function App route | Connector action | Connector operation ID |
| --- | --- | --- |
| `POST /api/GetXfaData` | Get XFA Form Data | `GetXfaData` |
| `POST /api/GetXfaSchema` | Get XFA Form Schema | `GetXfaFormSchema` |
| `POST /api/FillXfaData` | Fill XFA Form Data | `FillXfaData` |

## Power Automate flow

The custom connector contains a request policy for the `GetXfaData` and
`GetXfaFormSchema` connector operation IDs. It sets the outgoing `Content-Type`
to `application/octet-stream`. Power Platform custom-connector policies run at
the connector boundary, before the request reaches the backend.

1. Use SharePoint or OneDrive **Get file content** to obtain the PDF.
2. Pass its **File Content** output directly to **Get XFA Form Data** or **Get
   XFA Form Schema**. Do not add a `base64ToBinary(...)` or other conversion
   expression.
3. Pass **File Content** directly to **Fill XFA Form Data** as its source PDF,
   and provide the `formData` object and any desired write options.
4. Pass the action's PDF response directly to SharePoint or OneDrive **Create
   file**. Do not convert the response through a JSON or Base64 expression.

See [CustomConnector/README.md](CustomConnector/README.md) for connection
authentication and field-level flow guidance.

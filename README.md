# azure-function-examples

[![Build and deploy Java project to Azure Function App - restpdfformfiller](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/dev_restpdfformfiller(dev).yml/badge.svg?branch=dev)](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/dev_restpdfformfiller(dev).yml)


# Project Structure
## HttpTriggerFunctions
Holds the actual Azure functions that will be exposed to the internet. Performs HTTP/REST operations.

## <Future Name>
Sends and receives objects (what objects?) to the trigger functions above. Interfaces with OpenPDF to perform the requested operations.


# API
## Get Form Fields
Given a PDF form (XFA or regular Acroform), retreives a list of form fields correlated with their field descriptions/numbering.

## Fill Form
Given a PDF form and a JSON object of field values, returns the PDF form with the given fields containing the values passed in the JSON object.
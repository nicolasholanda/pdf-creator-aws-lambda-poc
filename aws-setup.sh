#!/bin/bash
aws sqs create-queue --queue-name pdf-generation
aws lambda create-function --function-name pdf-lambda --runtime java17 --handler com.github.nicolasholanda.PdfLambdaHandler::handleRequest --role arn:aws:iam::000000000000:role/lambda-role --zip-file fileb://target/pdf-creator-aws-lambda-poc-1.0-SNAPSHOT.jar
aws lambda create-event-source-mapping --function-name pdf-lambda --event-source-arn arn:aws:sqs:us-east-1:000000000000:pdf-generation
aws s3api create-bucket --bucket pdf-bucket
package com.github.nicolasholanda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.ses.SesClient;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.UUID;

public class PdfLambdaHandler implements RequestHandler<SQSEvent, Void> {
    private final String bucketName = System.getenv().getOrDefault("PDF_BUCKET", "pdf-bucket");
    //    private final String snsTopic = System.getenv("SNS_TOPIC");
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final SesClient sesClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PdfLambdaHandler() {
        Region region = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
        this.s3Client = S3Client.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .forcePathStyle(true) // Use path-style access for localstack compatibility
                .build();
        this.s3Presigner = S3Presigner.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        this.sesClient = SesClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage msg : event.getRecords()) {
            try {
                PdfRequestMessage request = parseMessage(msg.getBody());
                System.out.println("Received SQS event: " + request);

                byte[] pdfBytes = generatePdf(request.text());
                System.out.println("Generated PDF for text: " + request.text());

                String key = "pdfs/" + UUID.randomUUID() + ".pdf";
                PutObjectRequest objectRequest = PutObjectRequest.builder()
                        .bucket(bucketName).key(key).contentType("application/pdf").build();
                s3Client.putObject(objectRequest, RequestBody.fromBytes(pdfBytes));
                System.out.println("Uploaded PDF to S3 with key: " + key);

                String presignedUrl = generatePresignedUrl(key);
                String message = "Your PDF is available at: " + presignedUrl;
                sesClient.sendEmail(emailRequestBuilder -> emailRequestBuilder
                        .destination(destination -> destination.toAddresses(request.email()))
                        .message(messageBuilder -> messageBuilder
                                .subject(subjectBuilder -> subjectBuilder.data("Your PDF is ready!"))
                                .body(bodyBuilder -> bodyBuilder.text(contentBuilder -> contentBuilder.data(message))))
                        .source(request.email()));
                System.out.println("Sent email to: " + request.email());
            } catch (Exception e) {
                context.getLogger().log("Error processing message: " + e.getMessage());
            }
        }
        return null;
    }

    private PdfRequestMessage parseMessage(String body) {
        try {
            return objectMapper.readValue(body, PdfRequestMessage.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse message", e);
        }
    }

    private byte[] generatePdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDPageContentStream content = new PDPageContentStream(doc, page);
            Standard14Fonts.FontName fontName = Standard14Fonts.FontName.TIMES_ROMAN;
            PDFont font = new PDType1Font(fontName);
            content.beginText();
            content.setFont(font, 12);
            content.newLineAtOffset(50, 700);
            content.showText(text);
            content.endText();
            content.close();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private String generatePresignedUrl(String key) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(1))
                .getObjectRequest(b -> b.bucket(bucketName).key(key))
                .build();
        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
        return presignedRequest.url().toString();
    }

    private record PdfRequestMessage(String text, String email) {}
}

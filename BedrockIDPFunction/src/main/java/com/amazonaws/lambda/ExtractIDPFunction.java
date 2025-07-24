/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 */

package com.amazonaws.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.io.FilenameUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Lambda function that processes documents and images using Amazon Bedrock.
 * This function can handle PDF documents, extracting images from them if present,
 * and sending them to Bedrock for processing. It can also handle regular documents
 * and images directly.
 * 
 * The function stores the results in both S3 and DynamoDB for further processing.
 */
public class ExtractIDPFunction implements RequestHandler<Object, String> {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final BedrockRuntimeClient bedrockRuntimeClient = BedrockRuntimeClient.builder()
                                                                                  .httpClientBuilder(ApacheHttpClient.builder()
                                                                                                                     .connectionTimeout(Duration.ofSeconds(30))
                                                                                                                     .socketTimeout(Duration.ofMinutes(10)))
                                                                                  .build();
    private final S3Client s3Client = S3Client.builder().build();
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.builder().build();
    private final SsmClient ssmClient = SsmClient.builder().build();

    private final String modelID = Optional.ofNullable(System.getenv("Model_ID")).orElse("anthropic.claude-3-5-sonnet-20241022-v2:0");
    private final String sourceS3Bucket = System.getenv("Source_S3_Bucket");
    private final String outputS3Bucket = System.getenv("Output_S3_Bucket");
    private final String dynamoDBTableName = System.getenv("DynamoDB_Table_Name");
    private final String maxResponseToken = System.getenv("Max_Response_Token");
    private final Gson gson = new GsonBuilder().setPrettyPrinting()
            .create();
    private String defaultUserPrompt = "Extract the fields as JSON document, no other filler words, newline character are required in the output";
    private String defaultSystemPrompt = "Your response should be in JSON format.\\n Do not include any explanations, only provide a RFC8259 compliant JSON response without deviation.\\n Do not include markdown code blocks in your response.\\n";

    /**
     * Sanitizes a document name by removing special characters and normalizing whitespace.
     * 
     * @param name The document name to sanitize
     * @return The sanitized document name
     */
    private String sanitizeDocumentName(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\s\\-()\\[\\]]", "")
                   .replaceAll("\\s+", " ")
                   .trim();
    }
    


    /**
     * Extracts all images from a PDF document as JPEG bytes.
     *
     * @param pdfBytes The PDF document as a byte array
     * @return List of byte arrays containing images as JPEG, or empty list if no images found
     */
    private List<byte[]> extractImagesFromPdf(byte[] pdfBytes) {
        List<byte[]> images = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            for (PDPage page : document.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) continue;
                
                for (COSName name : resources.getXObjectNames()) {
                    PDXObject xObject = resources.getXObject(name);
                    if (xObject instanceof PDImageXObject) {
                        PDImageXObject image = (PDImageXObject) xObject;
                        BufferedImage bufferedImage = image.getImage();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        ImageIO.write(bufferedImage, "JPEG", baos);
                        images.add(baos.toByteArray());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error extracting images from PDF: " + e.getMessage());
        }
        return images;
    }

    /**
     * Handles the Lambda function request. This method processes documents and images using Amazon Bedrock.
     * For PDF documents with images, it extracts the images and sends them to Bedrock as JPEG images.
     * For regular documents, it sends them directly to Bedrock.
     * For regular images, it sends them directly to Bedrock.
     * 
     * @param event The Lambda function input event (S3 event or StepFunction event)
     * @param context The Lambda execution context
     * @return The JSON response from Amazon Bedrock
     */
    @Override
    public String handleRequest(Object event, Context context) {
        LambdaLogger logger = context.getLogger();
        String response = "200 OK";
        JsonNode eventJSONMap;
        String s3Key;

        GetParameterResponse bedrockIDPPromptSsm = ssmClient.getParameter(GetParameterRequest.builder()
                .name("BedrockIDP_Prompt_SSM")
                .build());

        String userPromptSSM = bedrockIDPPromptSsm.parameter().value();

        String userPromptEnv = System.getenv("Extraction_Prompt");

        if (!userPromptSSM.isEmpty()) {
            defaultUserPrompt = userPromptSSM;
            logger.log("using the User Prompt from the SSM Param Store :" + defaultUserPrompt);
        } else if (userPromptEnv != null && !userPromptEnv.isEmpty()) {
            defaultUserPrompt = "Extract the fields as JSON document, no other filler words, newline character are required in the output : " + userPromptEnv;
            logger.log("using the User Prompt from the Lambda Env :" + defaultUserPrompt);
        } else {
            logger.log("using the default User Prompt :" + defaultUserPrompt);
        }


        try {
            String eventString = objectMapper.writeValueAsString(event);
            logger.log("Event = " + eventString);
            eventJSONMap = objectMapper.readTree(eventString);
            // If the Event is from S3, else its assumed it's from StepFunctions
            if (eventJSONMap.has("Records")) {
                s3Key = eventJSONMap.path("Records").get(0).path("s3").path("object").path("key").asText();
                s3Key = URLDecoder.decode(s3Key, StandardCharsets.UTF_8);
            } else {
                s3Key = eventJSONMap.path("Key").asText();
                s3Key = URLDecoder.decode(s3Key, StandardCharsets.UTF_8);
            }
            logger.log("S3 Key = " + s3Key);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        ResponseInputStream<GetObjectResponse> s3ObjectResponse = s3Client.getObject(GetObjectRequest.builder()
                .bucket(sourceS3Bucket)
                .key(s3Key)
                .build());
        SdkBytes s3SDKBytes;
        try {
            s3SDKBytes = SdkBytes.fromByteArray(s3ObjectResponse.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String s3ContentType = s3ObjectResponse.response().contentType().toLowerCase();

        logger.log("S3 Content Type = " + s3ContentType);
        logger.log("\n");

        boolean document = Arrays.stream(DocumentFormat.values()).anyMatch(it -> s3ContentType.contains(it.name().toLowerCase()));
        logger.log("Document Type = " + document);
        logger.log("\n");

        // Check whether it's a PDF document and whether it contains images
        boolean isPdf = s3ContentType.contains("pdf");
        boolean containsImages = false; // Will be used later when storing to DynamoDB
        List<byte[]> extractedImages = new ArrayList<>(); // Store extracted image bytes for later use

        if (isPdf) {
            byte[] pdfBytes = s3SDKBytes.asByteArray();
            extractedImages = extractImagesFromPdf(pdfBytes);
            containsImages = !extractedImages.isEmpty();
            logger.log("PDF Contains Images = " + containsImages);
            logger.log("Number of images found: " + extractedImages.size());
            logger.log("\n");
        }

        boolean image = Arrays.stream(ImageFormat.values()).anyMatch(it -> s3ContentType.contains(it.name().toLowerCase()));
        logger.log("Image Type= " + image);
        logger.log("\n");

        if (!document && !image) {
            logger.log("Unsupported Content Type");
            logger.log("\n");
            return "Unsupported Content Type";
        }

        String baseName = sanitizeDocumentName(FilenameUtils.getBaseName(s3Key));
        
        // Process based on content type
        if (isPdf && containsImages) {
            logger.log("Processing PDF with images in a single request");
            logger.log("\n");
            
            // Create content blocks with all images
            List<ContentBlock> contentBlocks = new ArrayList<>();
            
            // Add text content block first
            contentBlocks.add(ContentBlock.builder()
                    .text(defaultUserPrompt)
                    .build());
            
            // Add each image as a separate content block
            for (int i = 0; i < extractedImages.size(); i++) {
                byte[] imageBytes = extractedImages.get(i);
                contentBlocks.add(ContentBlock.builder()
                        .image(ImageBlock.builder()
                                .format(ImageFormat.JPEG)
                                .source(ImageSource.builder()
                                        .bytes(SdkBytes.fromByteArray(imageBytes))
                                        .build())
                                .build())
                        .build());
            }
            
            // Create and send the request with all images
            ConverseRequest converseRequest = ConverseRequest.builder()
                    .modelId(modelID)
                    .messages(
                            Message.builder()
                                    .role(ConversationRole.USER)
                                    .content(contentBlocks.toArray(new ContentBlock[0]))
                                    .build())
                    .system(SystemContentBlock.builder()
                            .text(defaultSystemPrompt)
                            .build())
                    .inferenceConfig(InferenceConfiguration.builder()
                                    .maxTokens(Integer.valueOf(maxResponseToken))
                                    .temperature(0f)
                                    .topP(0f)
                                    .build())
                    .build();
            
            try {
                ConverseResponse converseResponse = bedrockRuntimeClient.converse(converseRequest);
                response = converseResponse.output().message().content().getFirst().text();
                logger.log("Processed " + extractedImages.size() + " images in a single request");
            } catch (Exception e) {
                logger.log("Error processing images with Bedrock: " + e.getMessage());
                response = "{}";
            }
            
        } else if (document) {
            // Process document normally
            List<ContentBlock> contentBlocks = new ArrayList<>();
            
            // Add text content block
            contentBlocks.add(ContentBlock.builder()
                    .text(defaultUserPrompt)
                    .build());
            
            // Add document content block
            contentBlocks.add(ContentBlock.builder()
                    .document(DocumentBlock.builder()
                            .name(baseName)
                            .format(DocumentFormat.PDF)
                            .source(DocumentSource.builder()
                                    .bytes(s3SDKBytes)
                                    .build())
                            .build())
                    .build());
                    
            ConverseRequest converseRequest = ConverseRequest.builder()
                    .modelId(modelID)
                    .messages(
                            Message.builder()
                                    .role(ConversationRole.USER)
                                    .content(contentBlocks.toArray(new ContentBlock[0]))
                                    .build())
                    .system(SystemContentBlock.builder()
                            .text(defaultSystemPrompt)
                            .build())
                    .inferenceConfig(InferenceConfiguration.builder()
                                    .maxTokens(Integer.valueOf(maxResponseToken))
                                    .temperature(0f)
                                    .topP(0f)
                                    .build())
                    .build();
    
            ConverseResponse converseResponse = bedrockRuntimeClient.converse(converseRequest);
            response = converseResponse.output().message().content().getFirst().text();
            
        } else {
            // Process single image normally
            List<ContentBlock> contentBlocks = new ArrayList<>();
            
            // Add text content block
            contentBlocks.add(ContentBlock.builder()
                    .text(defaultUserPrompt)
                    .build());
            
            // Add image content block
            contentBlocks.add(ContentBlock.builder()
                    .image(ImageBlock.builder()
                            .format(ImageFormat.JPEG)
                            .source(ImageSource.builder()
                                    .bytes(s3SDKBytes)
                                    .build())
                            .build())
                    .build());
                    
            ConverseRequest converseRequest = ConverseRequest.builder()
                    .modelId(modelID)
                    .messages(
                            Message.builder()
                                    .role(ConversationRole.USER)
                                    .content(contentBlocks.toArray(new ContentBlock[0]))
                                    .build())
                    .system(SystemContentBlock.builder()
                            .text(defaultSystemPrompt)
                            .build())
                    .inferenceConfig(InferenceConfiguration.builder()
                                    .maxTokens(Integer.valueOf(maxResponseToken))
                                    .temperature(0f)
                                    .topP(0f)
                                    .build())
                    .build();
    
            ConverseResponse converseResponse = bedrockRuntimeClient.converse(converseRequest);
            response = converseResponse.output().message().content().getFirst().text();
        }

        System.out.println("response = " + response);

        // Write the raw JSON response to output S3 Bucket
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(outputS3Bucket)
                .key(s3Key + "-Response.json")
                .contentType("application/json")
                .build(), RequestBody.fromBytes(response.getBytes()));

        // Write the raw JSON response to Amazon DynamoDB
        String finalResponse = response;

        Map<String, Object> map = new HashMap<>();
        Map<String, AttributeValue> finalMap = new HashMap<>();
        try {
            map = objectMapper.readValue(finalResponse, Map.class);

            finalMap.put("fileName", AttributeValue.builder()
                    .s(s3Key.trim())
                    .build());

            // Add PDF and image detection information
            if (s3ContentType.contains("pdf")) {
                finalMap.put("isPdf", AttributeValue.builder().bool(true).build());
                finalMap.put("containsImages", AttributeValue.builder().bool(containsImages).build());
                
                // Record if we used extracted images for processing
                boolean usedExtractedImages = isPdf && containsImages && !extractedImages.isEmpty();
                finalMap.put("usedExtractedImages", AttributeValue.builder().bool(usedExtractedImages).build());
                
                // Record the number of images extracted
                finalMap.put("imageCount", AttributeValue.builder().n(String.valueOf(extractedImages.size())).build());
                
                // Record image format used
                if (usedExtractedImages) {
                    finalMap.put("imageFormat", AttributeValue.builder().s("JPEG").build());
                }
            } else {
                finalMap.put("isPdf", AttributeValue.builder().bool(false).build());
            }

            map.forEach((key, value) -> finalMap.put(key, AttributeValue.builder()
                    .s(value != null ? value.toString() : "no data")
                    .build()));

            dynamoDbClient.putItem(builder -> builder
                    .tableName(dynamoDBTableName)
                    .item(finalMap)
                    .build());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        return response;
    }
}

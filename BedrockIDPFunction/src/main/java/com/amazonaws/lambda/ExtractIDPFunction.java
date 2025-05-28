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
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.sync.RequestBody;
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

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ExtractIDPFunction implements RequestHandler<Object, String> {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // AWS Services Client
    private final BedrockRuntimeClient bedrockRuntimeClient = BedrockRuntimeClient.builder().build();
    private final S3Client s3Client = S3Client.builder().build();
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.builder().build();
    private final SsmClient ssmClient = SsmClient.builder().build();

    // AWS Lambda Env Variables
    private final String modelID = Optional.ofNullable(System.getenv("Model_ID")).orElse("anthropic.claude-3-5-sonnet-20241022-v2:0");
    private final String sourceS3Bucket = System.getenv("Source_S3_Bucket");
    private final String outputS3Bucket = System.getenv("Output_S3_Bucket");
    private final String dynamoDBTableName = System.getenv("DynamoDB_Table_Name");
    private final String maxResponseToken = System.getenv("Max_Response_Token");
    private final Gson gson = new GsonBuilder().setPrettyPrinting()
            .create();
    private String defaultUserPrompt = "Extract the fields as JSON document, no other filler words, newline character are required in the output";
    private String defaultSystemPrompt = "Your response should be in JSON format.\\n Do not include any explanations, only provide a RFC8259 compliant JSON response without deviation.\\n Do not include markdown code blocks in your response.\\n";

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

        boolean image = Arrays.stream(ImageFormat.values()).anyMatch(it -> s3ContentType.contains(it.name().toLowerCase()));
        logger.log("Image Type= " + image);
        logger.log("\n");

        if (!document && !image) {
            logger.log("Unsupported Content Type");
            logger.log("\n");
            return "Unsupported Content Type";
        }

        String baseName = FilenameUtils.getBaseName(s3Key);
        ContentBlock contentBlock;
        if (document) {
            contentBlock = ContentBlock.builder()
                    .document(DocumentBlock.builder()
                            .name(baseName)
                            .format(DocumentFormat.PDF)
                            .source(DocumentSource.builder()
                                    .bytes(s3SDKBytes)
                                    .build())
                            .build())
                    .build();
        } else {
            contentBlock = ContentBlock.builder()
                    .image(ImageBlock.builder()
                            .format(ImageFormat.PNG)
                            .source(ImageSource.builder()
                                    .bytes(s3SDKBytes)
                                    .build())
                            .build())
                    .build();
        }

        ConverseRequest converseRequest = ConverseRequest.builder()
                .modelId(modelID)
                .messages(
                        Message.builder()
                                .role(ConversationRole.USER)
                                .content(ContentBlock.builder()
                                        .text(defaultUserPrompt)
                                        .build(), contentBlock)
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

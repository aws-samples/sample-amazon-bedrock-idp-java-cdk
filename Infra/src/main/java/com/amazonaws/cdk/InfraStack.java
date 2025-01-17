/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 */

package com.amazonaws.cdk;

import io.github.cdklabs.cdknag.NagPackSuppression;
import io.github.cdklabs.cdknag.NagSuppressions;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.notifications.LambdaDestination;
import software.amazon.awscdk.services.ssm.ParameterDataType;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.amazon.awscdk.services.stepfunctions.DefinitionBody;
import software.amazon.awscdk.services.stepfunctions.LogLevel;
import software.amazon.awscdk.services.stepfunctions.LogOptions;
import software.amazon.awscdk.services.stepfunctions.StateMachine;
import software.constructs.Construct;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class InfraStack extends Stack {
    public InfraStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public InfraStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        Key loggingBucketKey = Key.Builder.create(this, "LoggingBucketKey")
                .enableKeyRotation(true)
                .pendingWindow(Duration.days(7))
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        //Create SSM
        StringParameter promptSSM = StringParameter.Builder.create(this, "BedrockIDP_Prompt_SSM")
                .parameterName("BedrockIDP_Prompt_SSM")
                .dataType(ParameterDataType.TEXT)
                .stringValue("Prompt Template for Amazon Bedrock IDP")
                .build();

        //Create Bedrock Prompt Management
//        CfnPrompt.Builder.create(this, "BedrockIDP_Prompt")
//                .name("BedrockIDP_Prompt")
//                .variants(List.of(
//                        CfnPrompt.PromptVariantProperty.builder()
//                                .name("PromptTemplate")
//                                .templateConfiguration(CfnPrompt.PromptTemplateConfigurationProperty.builder()
//                                        .chat()
//                                        .build())
//                                .build()
//                ))
//                .build();

        Bucket loggingBucket = Bucket.Builder.create(this, "LoggingBucket")
                .enforceSsl(true)
                .encryption(BucketEncryption.KMS)
                .encryptionKey(loggingBucketKey)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .versioned(true)
                .removalPolicy(RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .build();

        Key sourceBucketKey = Key.Builder.create(this, "SourceBucketKey")
                .enableKeyRotation(true)
                .pendingWindow(Duration.days(7))
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Bucket sourceBucket = Bucket.Builder.create(this, "SourceBucket")
                .enforceSsl(true)
                .versioned(true)
                .encryption(BucketEncryption.KMS)
                .encryptionKey(sourceBucketKey)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .serverAccessLogsBucket(loggingBucket)
                .serverAccessLogsPrefix("sourceBucket/")
                .removalPolicy(RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .build();

        Key destinationBucketKey = Key.Builder.create(this, "DestinationBucketKey")
                .enableKeyRotation(true)
                .pendingWindow(Duration.days(7))
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Bucket destinationBucket = Bucket.Builder.create(this, "DestinationBucket")
                .enforceSsl(true)
                .versioned(true)
                .encryption(BucketEncryption.KMS)
                .encryptionKey(destinationBucketKey)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .serverAccessLogsBucket(loggingBucket)
                .serverAccessLogsPrefix("destinationBucket/")
                .removalPolicy(RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .build();

        // Create DynamoDB table to save the extracted information
        TableProps tablePropsBedrockIDP = TableProps.builder()
                .tableName("BedrockIDP-Table")
                .partitionKey(Attribute.builder()
                        .name("fileName")
                        .type(AttributeType.STRING)
                        .build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .pointInTimeRecovery(true)
                .encryption(TableEncryption.CUSTOMER_MANAGED)
                .encryptionKey(sourceBucketKey)
                .build();
        Table tableBedrockIDP = new Table(this, "BedrockIDPTableDDB", tablePropsBedrockIDP);


        // Create an IAM role for the Lambda function
        Role lambdaRole = Role.Builder.create(this, "LambdaRole")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .build();

        // Create a policy statement for CloudWatch Log Group
        PolicyStatement logGroupStatement = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("logs:CreateLogGroup"))
                .resources(List.of("arn:aws:logs:" + getRegion() + ":" + getAccount() + ":*"))
                .build();

        // Create a policy statement for Amazon Bedrock
        PolicyStatement bedrockStatement = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock:InvokeModel"))
                .resources(List.of("*"))
                .build();

        lambdaRole.addToPolicy(bedrockStatement);
        lambdaRole.addToPolicy(logGroupStatement);

        Function bedrockIDPFunction = Function.Builder.create(this, "BedrockIDPFunction")
                .runtime(Runtime.JAVA_21)
                .architecture(Architecture.X86_64)
                .handler("com.amazonaws.lambda.ExtractIDPFunction")
                .memorySize(1024)
                .timeout(Duration.minutes(5))
                .code(Code.fromAsset("../assets/ExtractIDPFunction.jar"))
                .environment(Map.of(
                        "Output_S3_Bucket", destinationBucket.getBucketName(),
                        "DEST_KEY_ID", destinationBucketKey.getKeyId(),
                        "Source_S3_Bucket", sourceBucket.getBucketName(),
                        "Model_ID", "anthropic.claude-3-5-sonnet-20240620-v1:0",
                        "DynamoDB_Table_Name", tableBedrockIDP.getTableName(),
                        "Extraction_Prompt", "Extract the following information from the document: Medical Record Number, Sample Collection Date (MM/DD/YYYY), Baby's Last Name, Baby's First Name"
                ))
                .role(lambdaRole)
                .build();

        // Add Object Created Notification to Source Bucket
        LambdaDestination lambdaDestination = new LambdaDestination(bedrockIDPFunction);
        sourceBucket.addObjectCreatedNotification(lambdaDestination);

        // AWS Lambda Execution Permission
        sourceBucket.grantRead(bedrockIDPFunction);
        destinationBucket.grantWrite(bedrockIDPFunction);
        tableBedrockIDP.grantReadWriteData(bedrockIDPFunction);
        promptSSM.grantRead(bedrockIDPFunction);

        // Create a policy statement for CloudWatch Logs
        PolicyStatement logsStatement = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("logs:CreateLogStream", "logs:PutLogEvents"))
                .resources(List.of("arn:aws:logs:" + getRegion() + ":" + getAccount() + ":log-group:/aws/lambda/" + bedrockIDPFunction.getFunctionName() + ":*"))
                .build();

        bedrockIDPFunction.getRole().attachInlinePolicy(Policy.Builder.create(this, "LogsPolicy")
                .document(PolicyDocument.Builder.create()
                        .statements(List.of(logsStatement))
                        .build())
                .build());

        try {
            String bedrockIDP_ASL = Files.readString(Path.of("../StepFunction/BedrockIDPStepFunction.json"));
            bedrockIDP_ASL = bedrockIDP_ASL.replace("<<BEDROCK-IDP-FUNCTION-NAME>>", bedrockIDPFunction.getFunctionArn());
            bedrockIDP_ASL = bedrockIDP_ASL.replace("<<SOURCE-S3-BUCKET-NAME>>", sourceBucket.getBucketName());

            // Create a new IAM role for the state machine
            Role stateMachineRoleBedrockIDP = Role.Builder.create(this, "stateMachineRoleBedrockIDP")
                    .assumedBy(new ServicePrincipal("states.amazonaws.com"))
                    .build();

            StateMachine bedrockIDPStateMachine = StateMachine.Builder.create(this, "BedrockIDPStateMachine")
                    .stateMachineName("BedrockIDPStateMachine")
                    .definitionBody(DefinitionBody.fromString(bedrockIDP_ASL))
                    .removalPolicy(RemovalPolicy.DESTROY)
                    .logs(LogOptions.builder()
                            .level(LogLevel.ALL)
                            .destination(LogGroup.Builder.create(this, "BedrockIDPStateMachine-LogGroup")
                                    .removalPolicy(RemovalPolicy.DESTROY)
                                    .build())
                            .includeExecutionData(true)
                            .build())
                    .role(stateMachineRoleBedrockIDP)
                    .tracingEnabled(true)
                    .build();

            sourceBucket.grantRead(stateMachineRoleBedrockIDP);
            bedrockIDPFunction.grantInvoke(stateMachineRoleBedrockIDP);

            Policy intakeInlinePolicy = Policy.Builder.create(this, "BedrockIDPStateMachine-StepFunctionPolicy")
                    .policyName("BedrockIDPStateMachine-StepFunctionPolicy")
                    .statements(List.of(PolicyStatement.Builder.create()
                            .actions(List.of("states:StartExecution", "states:DescribeExecution", "states:StopExecution"))
                            .resources(List.of(bedrockIDPStateMachine.getStateMachineArn()))
                            .build()))
                    .build();

            intakeInlinePolicy.attachToRole(stateMachineRoleBedrockIDP);

            CfnOutput.Builder.create(this, "BedrockIDPStateMachine-Name")
                    .description("AWS Step Function which process the S3 objects from source Bucket")
                    .value(bedrockIDPStateMachine.getStateMachineName())
                    .build();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        //CDK NAG Suppression's
        NagSuppressions.addResourceSuppressionsByPath(this, "/InfraStack/BucketNotificationsHandler050a0587b7544547bf325f094a3db834/Role/Resource",
                List.of(NagPackSuppression.builder()
                                .id("AwsSolutions-IAM4")
                                .reason("Internal CDK lambda needed to apply bucket notification configurations")
                                .appliesTo(List.of("Policy::arn:<AWS::Partition>:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"))
                                .build(),
                        NagPackSuppression.builder()
                                .id("AwsSolutions-IAM5")
                                .reason("Internal CDK lambda needed to apply bucket notification configurations")
                                .appliesTo(List.of("Resource::*"))
                                .build()));

        NagSuppressions.addStackSuppressions(this, List.of(NagPackSuppression.builder()
                .id("AwsSolutions-IAM5")
                .reason("Lambda needs access to create Log group and put log events which require *. Resources have to be '*' to make sure all models are accessible for bedrock:Invoke.")
                .build()));
    }
}

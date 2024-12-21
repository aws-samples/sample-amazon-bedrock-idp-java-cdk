package com.amazonaws.cdk;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.notifications.LambdaDestination;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
// import software.amazon.awscdk.Duration;
// import software.amazon.awscdk.services.sqs.Queue;

public class InfraStack extends Stack {
    public InfraStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public InfraStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        Key loggingBucketKey = Key.Builder.create(this, "LoggingBucketKey")
//                .alias("LoggingBucketKey")
                .enableKeyRotation(true)
                .pendingWindow(Duration.days(7))
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();


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
//                .alias("SourceBucketKey")
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
//                .alias("DestinationBucketKey")
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


        // Create a policy statement for Amazon Transcribe Logs
        PolicyStatement bedrockStatement = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock:*"))
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
                        "OUTPUT_BUCKET", destinationBucket.getBucketName(),
                        "DEST_KEY_ID", destinationBucketKey.getKeyId()))
                .role(lambdaRole)
                .build();

        // Add Object Created Notification to Source Bucket
        LambdaDestination lambdaDestination = new LambdaDestination(bedrockIDPFunction);
        sourceBucket.addObjectCreatedNotification(lambdaDestination);

        sourceBucket.grantRead(bedrockIDPFunction);
        destinationBucket.grantWrite(bedrockIDPFunction);

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
    }
}

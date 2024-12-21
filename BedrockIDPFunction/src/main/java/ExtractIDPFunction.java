import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockdataautomationruntime.BedrockDataAutomationRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;

public class ExtractIDPFunction implements RequestHandler<S3Event, String> {
    private final BedrockDataAutomationRuntimeClient bdaRuntimeClient = BedrockDataAutomationRuntimeClient.builder().region(Region.US_WEST_2).build();

    private final BedrockRuntimeClient bedrockRuntimeClient = BedrockRuntimeClient.builder()
            .region(Region.US_WEST_2)
            .build();

    private final S3Client s3Client = S3Client.builder().region(Region.US_WEST_2).build();

    private final String modelID = "anthropic.claude-3-5-sonnet-20240620-v1:0";
    private final String inputPrompt = "Extract the IDP from the following text: " + System.getenv("inputPrompt");

    @Override
    public String handleRequest(S3Event event, Context context) {
        String response = "200 OK";
        String s3Key = event.getRecords().getFirst().getS3().getObject().getKey();
        String s3Bucket = event.getRecords().getFirst().getS3().getBucket().getName();
        ResponseInputStream<GetObjectResponse> s3ObjectResponse = s3Client.getObject(GetObjectRequest.builder()
                .bucket(s3Bucket)
                .key(s3Key)
                .build());
        SdkBytes s3SDKBytes;
        try {
            s3SDKBytes = SdkBytes.fromByteArray(s3ObjectResponse.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ConverseResponse converseResponse = bedrockRuntimeClient.converse(ConverseRequest.builder()
                .modelId(modelID)
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.builder()
                                .text(inputPrompt)
                                .build(), ContentBlock.builder()
                                .document(DocumentBlock.builder()
                                        .name(s3Key)
                                        .format("pdf")
                                        .source(DocumentSource.builder()
                                                .bytes(s3SDKBytes)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build());

        response = converseResponse.output().message().content().getFirst().text();

        return response;
    }
}

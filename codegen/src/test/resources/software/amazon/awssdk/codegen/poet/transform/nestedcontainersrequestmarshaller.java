package software.amazon.awssdk.services.jsonprotocoltests.transform;

import software.amazon.awssdk.annotations.Generated;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.core.Request;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.http.HttpMethodName;
import software.amazon.awssdk.core.runtime.transform.Marshaller;
import software.amazon.awssdk.protocols.core.OperationInfo;
import software.amazon.awssdk.protocols.core.ProtocolMarshaller;
import software.amazon.awssdk.protocols.json.BaseAwsJsonProtocolFactory;
import software.amazon.awssdk.services.jsonprotocoltests.model.NestedContainersRequest;
import software.amazon.awssdk.utils.Validate;

/**
 * {@link NestedContainersRequest} Marshaller
 */
@Generated("software.amazon.awssdk:codegen")
@SdkInternalApi
public class NestedContainersRequestMarshaller implements Marshaller<Request<NestedContainersRequest>, NestedContainersRequest> {
    private static final OperationInfo SDK_OPERATION_BINDING = OperationInfo.builder().requestUri("/")
                                                                            .httpMethodName(HttpMethodName.POST).hasExplicitPayloadMember(false).hasPayloadMembers(true).build();

    private final BaseAwsJsonProtocolFactory protocolFactory;

    public NestedContainersRequestMarshaller(BaseAwsJsonProtocolFactory protocolFactory) {
        this.protocolFactory = protocolFactory;
    }

    @Override
    public Request<NestedContainersRequest> marshall(NestedContainersRequest nestedContainersRequest) {
        Validate.paramNotNull(nestedContainersRequest, "nestedContainersRequest");
        try {
            ProtocolMarshaller<Request<NestedContainersRequest>> protocolMarshaller = protocolFactory.createProtocolMarshaller(
                SDK_OPERATION_BINDING, nestedContainersRequest);
            return protocolMarshaller.marshall(nestedContainersRequest);
        } catch (Exception e) {
            throw SdkClientException.builder().message("Unable to marshall request to JSON: " + e.getMessage()).cause(e).build();
        }
    }
}


/*
 * Copyright 2010-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.protocols.query;

import java.util.function.Supplier;
import software.amazon.awssdk.annotations.SdkProtectedApi;
import software.amazon.awssdk.awscore.AwsResponse;
import software.amazon.awssdk.core.Request;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.core.http.HttpResponseHandler;
import software.amazon.awssdk.protocols.core.OperationInfo;
import software.amazon.awssdk.protocols.core.ProtocolMarshaller;
import software.amazon.awssdk.protocols.query.internal.marshall.QueryProtocolMarshaller;
import software.amazon.awssdk.protocols.query.internal.unmarshall.AwsQueryResponseHandler;
import software.amazon.awssdk.protocols.query.internal.unmarshall.QueryProtocolUnmarshaller;

/**
 * Protocol factory for the AWS/Query protocol.
 */
@SdkProtectedApi
public class AwsQueryProtocolFactory {

    protected AwsQueryProtocolFactory(Builder builder) {
    }

    public <T extends software.amazon.awssdk.awscore.AwsRequest> ProtocolMarshaller<Request<T>> createProtocolMarshaller(
        OperationInfo operationInfo, T origRequest) {
        return QueryProtocolMarshaller.builder(origRequest)
                                      .operationInfo(operationInfo)
                                      .build();
    }

    public <T extends AwsResponse> HttpResponseHandler<T> createResponseHandler(Supplier<SdkPojo> pojoSupplier) {
        return new AwsQueryResponseHandler<>(new QueryProtocolUnmarshaller<>(true), r -> pojoSupplier.get());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder<T extends Builder> {

        protected Builder() {
        }

        public AwsQueryProtocolFactory build() {
            return new AwsQueryProtocolFactory(this);
        }
    }
}

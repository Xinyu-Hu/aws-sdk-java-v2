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

package software.amazon.awssdk.codegen.poet.client;

import static com.squareup.javapoet.TypeSpec.Builder;
import static java.util.Collections.singletonList;
import static software.amazon.awssdk.codegen.poet.client.ClientClassUtils.applyPaginatorUserAgentMethod;
import static software.amazon.awssdk.codegen.poet.client.ClientClassUtils.applySignerOverrideMethod;
import static software.amazon.awssdk.codegen.poet.client.ClientClassUtils.getCustomResponseHandler;
import static software.amazon.awssdk.codegen.poet.client.SyncClientClass.getProtocolSpecs;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.awscore.client.handler.AwsAsyncClientHandler;
import software.amazon.awssdk.awscore.eventstream.EventStreamTaggedUnionJsonMarshaller;
import software.amazon.awssdk.awscore.internal.client.handler.AwsClientHandlerUtils;
import software.amazon.awssdk.codegen.emitters.GeneratorTaskParams;
import software.amazon.awssdk.codegen.model.intermediate.IntermediateModel;
import software.amazon.awssdk.codegen.model.intermediate.MemberModel;
import software.amazon.awssdk.codegen.model.intermediate.OperationModel;
import software.amazon.awssdk.codegen.model.intermediate.ShapeModel;
import software.amazon.awssdk.codegen.poet.PoetExtensions;
import software.amazon.awssdk.codegen.poet.PoetUtils;
import software.amazon.awssdk.codegen.poet.StaticImport;
import software.amazon.awssdk.codegen.poet.client.specs.ProtocolSpec;
import software.amazon.awssdk.codegen.poet.eventstream.EventStreamUtils;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.core.client.config.SdkClientConfiguration;
import software.amazon.awssdk.core.client.handler.AsyncClientHandler;
import software.amazon.awssdk.protocols.json.AwsJsonProtocolFactory;
import software.amazon.awssdk.utils.CompletableFutureUtils;
import software.amazon.awssdk.utils.FunctionalUtils;

public final class AsyncClientClass extends AsyncClientInterface {
    private final IntermediateModel model;
    private final PoetExtensions poetExtensions;
    private final ClassName className;
    private final ProtocolSpec protocolSpec;

    public AsyncClientClass(GeneratorTaskParams dependencies) {
        super(dependencies.getModel());
        this.model = dependencies.getModel();
        this.poetExtensions = dependencies.getPoetExtensions();
        this.className = poetExtensions.getClientClass(model.getMetadata().getAsyncClient());
        this.protocolSpec = getProtocolSpecs(poetExtensions, model);
    }

    @Override
    public TypeSpec poetSpec() {
        ClassName interfaceClass = poetExtensions.getClientClass(model.getMetadata().getAsyncInterface());
        Builder classBuilder = PoetUtils.createClassBuilder(className);
        classBuilder.addAnnotation(SdkInternalApi.class)
                    .addModifiers(Modifier.FINAL)
                    .addField(FieldSpec.builder(ClassName.get(Logger.class), "log")
                                       .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                                       .initializer("$T.getLogger($T.class)", LoggerFactory.class,
                                                    className)
                                       .build())
                    .addField(AsyncClientHandler.class, "clientHandler", Modifier.PRIVATE, Modifier.FINAL)
                    .addField(protocolSpec.protocolFactory(model))
                    .addSuperinterface(interfaceClass)
                    .addJavadoc("Internal implementation of {@link $1T}.\n\n@see $1T#builder()",
                                interfaceClass)
                    .addMethod(constructor(classBuilder))
                    .addMethod(nameMethod())
                    .addMethods(operations())
                    .addMethod(closeMethod())
                    .addMethods(protocolSpec.additionalMethods())
                    .addFields(protocolSpec.additionalFields())
                    .addMethod(protocolSpec.initProtocolFactory(model));

        // Kinesis doesn't support CBOR for STS yet so need another protocol factory for JSON
        if (model.getMetadata().isCborProtocol()) {
            classBuilder.addField(AwsJsonProtocolFactory.class, "jsonProtocolFactory", Modifier.PRIVATE, Modifier.FINAL);
        }

        if (model.hasPaginators()) {
            classBuilder.addMethod(applyPaginatorUserAgentMethod(poetExtensions, model));
        }

        if (model.containsRequestSigners() || model.containsRequestEventStreams()) {
            classBuilder.addMethod(applySignerOverrideMethod(poetExtensions, model));
        }

        protocolSpec.createErrorResponseHandler().ifPresent(classBuilder::addMethod);

        return classBuilder.build();
    }

    private MethodSpec constructor(Builder classBuilder) {
        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                                               .addModifiers(Modifier.PROTECTED)
                                               .addParameter(SdkClientConfiguration.class, "clientConfiguration")
                                               .addStatement("this.clientHandler = new $T(clientConfiguration)",
                                                             AwsAsyncClientHandler.class);
        FieldSpec protocolFactoryField = protocolSpec.protocolFactory(model);
        if (model.getMetadata().isJsonProtocol()) {
            builder.addStatement("this.$N = init($T.builder()).build()", protocolFactoryField.name,
                                protocolFactoryField.type);
        } else {
            builder.addStatement("this.$N = init()", protocolFactoryField.name);
        }
        if (model.getMetadata().isCborProtocol()) {
            builder.addStatement("this.jsonProtocolFactory = init($T.builder()).build()", AwsJsonProtocolFactory.class);
        }
        if (hasOperationWithEventStreamOutput()) {
            classBuilder.addField(FieldSpec.builder(ClassName.get(Executor.class), "executor",
                                                    Modifier.PRIVATE, Modifier.FINAL)
                                           .build());
            builder.addStatement("this.executor = clientConfiguration.option($T.FUTURE_COMPLETION_EXECUTOR)",
                                 SdkAdvancedAsyncClientOption.class);
        }
        return builder.build();
    }

    private boolean hasOperationWithEventStreamOutput() {
        return model.getOperations().values().stream().anyMatch(OperationModel::hasEventStreamOutput);
    }

    private MethodSpec nameMethod() {
        return MethodSpec.methodBuilder("serviceName")
                         .addAnnotation(Override.class)
                         .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                         .returns(String.class)
                         .addStatement("return SERVICE_NAME")
                         .build();
    }

    private MethodSpec closeMethod() {
        return MethodSpec.methodBuilder("close")
                         .addAnnotation(Override.class)
                         .addModifiers(Modifier.PUBLIC)
                         .addStatement("$N.close()", "clientHandler")
                         .build();
    }

    @Override
    protected MethodSpec.Builder operationBody(MethodSpec.Builder builder, OperationModel opModel) {
        ClassName returnType = poetExtensions.getModelClass(opModel.getReturnType().getReturnType());

        builder.addModifiers(Modifier.PUBLIC)
                      .addAnnotation(Override.class)
                      .beginControlFlow("try")
                          .addCode(ClientClassUtils.callApplySignerOverrideMethod(opModel))
                          .addCode(getCustomResponseHandler(opModel, returnType)
                                       .orElseGet(() -> protocolSpec.responseHandler(model, opModel)))
                          .addCode(protocolSpec.errorResponseHandler(opModel))
                          .addCode(eventToByteBufferPublisher(opModel))
                          .addCode(protocolSpec.asyncExecutionHandler(model, opModel))
               .endControlFlow()
               .beginControlFlow("catch ($T t)", Throwable.class);

        // For streaming operations we also want to notify the response handler of any exception.
        if (opModel.hasStreamingOutput() || opModel.hasEventStreamOutput()) {
            String paramName = opModel.hasStreamingOutput() ? "asyncResponseTransformer" : "asyncResponseHandler";
            builder.addStatement("runAndLogError(log, \"Exception thrown in exceptionOccurred callback, ignoring\",\n" +
                                 "() -> $N.exceptionOccurred(t))", paramName);
        }

        return builder.addStatement("return $T.failedFuture(t)", CompletableFutureUtils.class)
                      .endControlFlow();
    }

    @Override
    protected MethodSpec.Builder paginatedMethodBody(MethodSpec.Builder builder, OperationModel opModel) {
        return builder.addModifiers(Modifier.PUBLIC)
                      .addStatement("return new $T(this, applyPaginatorUserAgent($L))",
                                    poetExtensions.getResponseClassForPaginatedAsyncOperation(opModel.getOperationName()),
                                    opModel.getInput().getVariableName());
    }

    @Override
    public ClassName className() {
        return className;
    }

    @Override
    public Iterable<StaticImport> staticImports() {
        return singletonList(StaticImport.staticMethodImport(FunctionalUtils.class, "runAndLogError"));
    }

    private CodeBlock eventToByteBufferPublisher(OperationModel opModel) {
        if (!opModel.hasEventStreamInput()) {
            return CodeBlock.builder().build();
        }

        ShapeModel eventStreamShape = EventStreamUtils.getEventStreamInRequest(opModel.getInputShape());
        CodeBlock code = CodeBlock.builder()
                                  .add(createEventStreamTaggedUnionJsonMarshaller(eventStreamShape))
                                  .addStatement("$1T eventPublisher = $2T.adapt($3L)",
                                                ParameterizedTypeName.get(
                                                    ClassName.get(SdkPublisher.class),
                                                    eventStreamType(eventStreamShape)),
                                                SdkPublisher.class,
                                                EVENT_PUBLISHER_PARAM_NAME)
                                  .add("$T adapted = eventPublisher.map(event -> eventMarshaller.marshall(event))",
                                       ParameterizedTypeName.get(Publisher.class, ByteBuffer.class))
                                  .add(".map($T::encodeEventStreamRequestToByteBuffer);", AwsClientHandlerUtils.class)
                                  .build();

        return code;
    }

    private CodeBlock createEventStreamTaggedUnionJsonMarshaller(ShapeModel eventStreamShape) {
        CodeBlock.Builder builder = CodeBlock.builder().add("$1T eventMarshaller = $1T.builder()",
                                                            EventStreamTaggedUnionJsonMarshaller.class);

        List<String> eventNames = EventStreamUtils.getEventMembers(eventStreamShape)
                                                  .map(MemberModel::getC2jName)
                                                  .collect(Collectors.toList());

        eventNames.forEach(event -> builder.add(".putMarshaller($T.class, new $T(protocolFactory))",
                                                      poetExtensions.getModelClass(event),
                                                      poetExtensions.getTransformClass(event + "Marshaller")));

        builder.add(".build();");
        return builder.build();
    }

    private TypeName eventStreamType(ShapeModel shapeModel) {
        return poetExtensions.getModelClass(shapeModel.getShapeName());
    }
}

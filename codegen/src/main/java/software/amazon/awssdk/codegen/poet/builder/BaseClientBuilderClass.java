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

package software.amazon.awssdk.codegen.poet.builder;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import java.util.List;
import javax.lang.model.element.Modifier;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.QueryStringSigner;
import software.amazon.awssdk.awscore.client.builder.AwsDefaultClientBuilder;
import software.amazon.awssdk.codegen.internal.Utils;
import software.amazon.awssdk.codegen.model.intermediate.IntermediateModel;
import software.amazon.awssdk.codegen.model.service.AuthType;
import software.amazon.awssdk.codegen.poet.ClassSpec;
import software.amazon.awssdk.codegen.poet.PoetUtils;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.core.client.config.SdkClientConfiguration;
import software.amazon.awssdk.core.client.config.SdkClientOption;
import software.amazon.awssdk.core.interceptor.ClasspathInterceptorChainFactory;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.signer.Signer;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.utils.AttributeMap;
import software.amazon.awssdk.utils.CollectionUtils;
import software.amazon.awssdk.utils.StringUtils;

public class BaseClientBuilderClass implements ClassSpec {
    private final IntermediateModel model;
    private final ClassName builderInterfaceName;
    private final ClassName builderClassName;
    private final String basePackage;

    public BaseClientBuilderClass(IntermediateModel model) {
        this.model = model;
        this.basePackage = model.getMetadata().getFullClientPackageName();
        this.builderInterfaceName = ClassName.get(basePackage, model.getMetadata().getBaseBuilderInterface());
        this.builderClassName = ClassName.get(basePackage, model.getMetadata().getBaseBuilder());
    }

    @Override
    public TypeSpec poetSpec() {
        TypeSpec.Builder builder =
                PoetUtils.createClassBuilder(builderClassName)
                         .addModifiers(Modifier.ABSTRACT)
                         .addAnnotation(SdkInternalApi.class)
                         .addTypeVariable(PoetUtils.createBoundedTypeVariableName("B", builderInterfaceName, "B", "C"))
                         .addTypeVariable(TypeVariableName.get("C"))
                         .superclass(PoetUtils.createParameterizedTypeName(AwsDefaultClientBuilder.class, "B", "C"))
                         .addJavadoc("Internal base class for {@link $T} and {@link $T}.",
                                     ClassName.get(basePackage, model.getMetadata().getSyncBuilder()),
                                     ClassName.get(basePackage, model.getMetadata().getAsyncBuilder()));

        builder.addMethod(serviceEndpointPrefixMethod());
        builder.addMethod(mergeServiceDefaultsMethod());
        builder.addMethod(finalizeServiceConfigurationMethod());
        builder.addMethod(defaultSignerMethod());
        builder.addMethod(signingNameMethod());

        if (model.getCustomizationConfig().getServiceSpecificClientConfigClass() != null) {
            builder.addMethod(setServiceConfigurationMethod())
                   .addMethod(beanStyleSetServiceConfigurationMethod());
        }

        addServiceHttpConfigIfNeeded(builder, model);

        return builder.build();
    }

    private MethodSpec signingNameMethod() {
        return MethodSpec.methodBuilder("signingName")
                         .addAnnotation(Override.class)
                         .addModifiers(Modifier.PROTECTED, Modifier.FINAL)
                         .returns(String.class)
                         .addCode("return $S;", model.getMetadata().getSigningName())
                         .build();
    }

    private MethodSpec defaultSignerMethod() {
        return MethodSpec.methodBuilder("defaultSigner")
                         .returns(Signer.class)
                         .addModifiers(Modifier.PRIVATE)
                         .addCode(signerDefinitionMethodBody())
                         .build();
    }

    private MethodSpec serviceEndpointPrefixMethod() {
        return MethodSpec.methodBuilder("serviceEndpointPrefix")
                         .addAnnotation(Override.class)
                         .addModifiers(Modifier.PROTECTED, Modifier.FINAL)
                         .returns(String.class)
                         .addCode("return $S;", model.getMetadata().getEndpointPrefix())
                         .build();
    }

    private MethodSpec mergeServiceDefaultsMethod() {
        boolean crc32FromCompressedDataEnabled = model.getCustomizationConfig().isCalculateCrc32FromCompressedData();

        MethodSpec.Builder builder = MethodSpec.methodBuilder("mergeServiceDefaults")
                                               .addAnnotation(Override.class)
                                               .addModifiers(Modifier.PROTECTED, Modifier.FINAL)
                                               .returns(SdkClientConfiguration.class)
                                               .addParameter(SdkClientConfiguration.class, "config")
                                               .addCode("return config.merge(c -> c.option($T.SIGNER, defaultSigner())\n",
                                                        SdkAdvancedClientOption.class)
                                               .addCode("                          .option($T"
                                                        + ".CRC32_FROM_COMPRESSED_DATA_ENABLED, $L)",
                                                        SdkClientOption.class, crc32FromCompressedDataEnabled);

        if (StringUtils.isNotBlank(model.getCustomizationConfig().getCustomRetryPolicy())) {
            builder.addCode(".option($T.RETRY_POLICY, $T.defaultPolicy())", SdkClientOption.class,
                            PoetUtils.classNameFromFqcn(model.getCustomizationConfig().getCustomRetryPolicy()));
        }
        builder.addCode(");");
        return builder.build();
    }

    private MethodSpec finalizeServiceConfigurationMethod() {
        String requestHandlerDirectory = Utils.packageToDirectory(model.getMetadata().getFullClientPackageName());
        String requestHandlerPath = String.format("%s/execution.interceptors", requestHandlerDirectory);

        return MethodSpec.methodBuilder("finalizeServiceConfiguration")
                         .addAnnotation(Override.class)
                         .addModifiers(Modifier.PROTECTED, Modifier.FINAL)
                         .returns(SdkClientConfiguration.class)
                         .addParameter(SdkClientConfiguration.class, "config")
                         .addCode("$1T interceptorFactory = new $1T();\n", ClasspathInterceptorChainFactory.class)
                         .addCode("$T<$T> interceptors = interceptorFactory.getInterceptors($S);\n",
                                  List.class, ExecutionInterceptor.class, requestHandlerPath)
                         .addCode("interceptors = $T.mergeLists(interceptors, config.option($T.EXECUTION_INTERCEPTORS));\n",
                                  CollectionUtils.class, SdkClientOption.class)
                         .addCode("return config.toBuilder()\n" +
                                  "             .option($T.EXECUTION_INTERCEPTORS, interceptors)\n" +
                                  "             .build();", SdkClientOption.class)
                         .build();
    }

    private MethodSpec setServiceConfigurationMethod() {
        ClassName serviceConfiguration = ClassName.get(basePackage,
                                                        model.getCustomizationConfig().getServiceSpecificClientConfigClass());
        return MethodSpec.methodBuilder("serviceConfiguration")
                         .addModifiers(Modifier.PUBLIC)
                         .returns(TypeVariableName.get("B"))
                         .addParameter(serviceConfiguration, "serviceConfiguration")
                         .addStatement("clientConfiguration.option($T.SERVICE_CONFIGURATION, serviceConfiguration)",
                                       SdkClientOption.class)
                         .addStatement("return thisBuilder()")
                         .build();
    }

    private MethodSpec beanStyleSetServiceConfigurationMethod() {
        ClassName serviceConfiguration = ClassName.get(basePackage,
                                                        model.getCustomizationConfig().getServiceSpecificClientConfigClass());
        return MethodSpec.methodBuilder("setServiceConfiguration")
                         .addModifiers(Modifier.PUBLIC)
                         .addParameter(serviceConfiguration, "serviceConfiguration")
                         .addStatement("serviceConfiguration(serviceConfiguration)")
                         .build();
    }

    private void addServiceHttpConfigIfNeeded(TypeSpec.Builder builder, IntermediateModel model) {
        String serviceDefaultFqcn = model.getCustomizationConfig().getServiceSpecificHttpConfig();
        boolean supportsH2 = model.getMetadata().supportsH2();

        if (serviceDefaultFqcn != null || supportsH2) {
            builder.addMethod(serviceSpecificHttpConfigMethod(serviceDefaultFqcn, supportsH2));
        }
    }

    private MethodSpec serviceSpecificHttpConfigMethod(String serviceDefaultFqcn, boolean supportsH2) {
        return MethodSpec.methodBuilder("serviceHttpConfig")
                         .addAnnotation(Override.class)
                         .addModifiers(Modifier.PROTECTED, Modifier.FINAL)
                         .returns(AttributeMap.class)
                         .addCode(serviceSpecificHttpConfigMethodBody(serviceDefaultFqcn, supportsH2))
                         .build();
    }

    private CodeBlock serviceSpecificHttpConfigMethodBody(String serviceDefaultFqcn, boolean supportsH2) {
        CodeBlock.Builder builder =  CodeBlock.builder();

        if (serviceDefaultFqcn != null) {
            builder.addStatement("$T result = $T.defaultHttpConfig()",
                                 AttributeMap.class,
                                 PoetUtils.classNameFromFqcn(model.getCustomizationConfig().getServiceSpecificHttpConfig()));
        } else {
            builder.addStatement("$1T result = $1T.empty()", AttributeMap.class);
        }

        if (supportsH2) {
            builder.addStatement("return result.merge(AttributeMap.builder()"
                                 + ".put($T.PROTOCOL, $T.HTTP2)"
                                 + ".build())",
                                 SdkHttpConfigurationOption.class, Protocol.class);
        } else {
            builder.addStatement("return result");
        }

        return builder.build();
    }

    private CodeBlock signerDefinitionMethodBody() {
        AuthType authType = model.getMetadata().getAuthType();
        switch (authType) {
            case V4:
                return v4SignerDefinitionMethodBody();
            case V2:
                return v2SignerDefinitionMethodBody();
            case S3:
                return s3SignerDefinitionMethodBody();
            default:
                throw new UnsupportedOperationException("Unsupported signer type: " + authType);
        }
    }

    private CodeBlock v4SignerDefinitionMethodBody() {
        return CodeBlock.of("return $T.create();", Aws4Signer.class);
    }

    private CodeBlock v2SignerDefinitionMethodBody() {
        return CodeBlock.of("return $T.create();", QueryStringSigner.class);
    }

    private CodeBlock s3SignerDefinitionMethodBody() {
        return CodeBlock.of("return $T.create();\n",
                            ClassName.get("software.amazon.awssdk.auth.signer", "AwsS3V4Signer"));
    }

    @Override
    public ClassName className() {
        return builderClassName;
    }
}

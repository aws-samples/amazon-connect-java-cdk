package com.amazonaws.cdk;

import software.amazon.awscdk.*;
import software.amazon.awscdk.customresources.*;
import software.amazon.awscdk.services.connect.CfnInstance;
import software.amazon.awscdk.services.connect.CfnInstanceStorageConfig;
import software.amazon.awscdk.services.connect.CfnPhoneNumber;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.s3.Bucket;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
// import software.amazon.awscdk.Duration;
// import software.amazon.awscdk.services.sqs.Queue;

public class AmazonConnectStack extends Stack {
    public AmazonConnectStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public AmazonConnectStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);


        CfnParameter connectInstanceAlias = CfnParameter.Builder.create(this, "connectInstanceAlias")
                .description("Enter Unique Connect Instance Alias")
                .defaultValue("connect-"+System.currentTimeMillis())
                .type("String")
                .build();

        CfnParameter userMgmtType = CfnParameter.Builder.create(this, "userManagementType")
                .description("default is CONNECT_MANAGED, select SAML if you want your connect instance to use centralized User Management")
                .defaultValue("CONNECT_MANAGED")
                .type("String")
                .allowedValues(List.of("SAML", "CONNECT_MANAGED"))
                .build();

        CfnParameter adminUser = CfnParameter.Builder.create(this, "connectAdminUserEmailId")
                .description("Enter Connect Admin User Email Id")
                .defaultValue("aruthan@amazon.com")
                .type("String")
                .build();

//        CfnParameter samlData = CfnParameter.Builder.create(this, "Identity SAML")
//                .description("SAML 2.0 XML Data")
//                .type("String")
//                .build();




        // Amazon Connect Instance
        CfnInstance amazonConnect = CfnInstance.Builder.create(this, "connect-example")
                .instanceAlias(connectInstanceAlias.getValueAsString())
                .attributes(CfnInstance.AttributesProperty.builder()
                        .autoResolveBestVoices(true)
                        .contactflowLogs(true)
                        .contactLens(true)
                        .inboundCalls(true)
                        .outboundCalls(true)
                        .build())
                .identityManagementType(userMgmtType.getValueAsString())
                .build();

        // Claim Phone Number for Amazon Connect Instance
        CfnPhoneNumber.Builder.create(this, "connect-example-phone-number")
                .countryCode("US")
                .targetArn(amazonConnect.getAttrArn())
                .type("TOLL_FREE")
                .build();

        // API Call to get Routing Profile ARN
        AwsSdkCall listRoutingProfiles = AwsSdkCall.builder()
                .service("Connect")
                .action("listRoutingProfiles")
                .parameters(Map.of("InstanceId", amazonConnect.getAttrId()))
                .physicalResourceId(PhysicalResourceId.of("CustomProviderListRoutingProfiles"))
                .build();

        AwsCustomResource awsCustomResourceListRoutingProfiles = AwsCustomResource.Builder.create(this, "CustomProviderListRoutingProfiles ")
                .onCreate(listRoutingProfiles)
                .policy(AwsCustomResourcePolicy.fromSdkCalls(SdkCallsPolicyOptions.builder()
                        .resources(AwsCustomResourcePolicy.ANY_RESOURCE)
                        .build()))
                .build();

        String routingProfileARN = awsCustomResourceListRoutingProfiles.getResponseField("RoutingProfileSummaryList.0.Arn");

        // API Call to get Security Profile ARN
        AwsSdkCall listSecurityProfiles = AwsSdkCall.builder()
                .service("Connect")
                .action("listSecurityProfiles")
                .parameters(Map.of("InstanceId", amazonConnect.getAttrId()))
                .physicalResourceId(PhysicalResourceId.of("CustomProviderListSecurityProfiles"))
                .build();

        AwsCustomResource awsCustomResourceListSecurityProfiles = AwsCustomResource.Builder.create(this, "CustomProviderListSecurityProfiles")
                .onCreate(listSecurityProfiles)
                .policy(AwsCustomResourcePolicy.fromSdkCalls(SdkCallsPolicyOptions.builder()
                        .resources(AwsCustomResourcePolicy.ANY_RESOURCE)
                        .build()))
                .build();

        String securityProfileARN = awsCustomResourceListSecurityProfiles.getResponseField("SecurityProfileSummaryList.3.Arn");

//        CfnUser amazonAdminUser = CfnUser.Builder.create(this, "connect-example-admin")
//                .username(adminUser.getValueAsString())
//                .password("Password@123")
//                .instanceArn(amazonConnect.getAttrArn())
//                .identityInfo(CfnUser.UserIdentityInfoProperty.builder()
//                        .firstName("Admin")
//                        .lastName("Last")
//                        .build())
//                .phoneConfig(CfnUser.UserPhoneConfigProperty.builder()
//                        .phoneType("SOFT_PHONE")
//                        .build())
//                .routingProfileArn(routingProfileARN)
//                .securityProfileArns(List.of(securityProfileARN))
//                .build();

        Key amazonConnectManagedKeyAlias = Key.Builder.create(this, "AmazonConnectManagedKeyAlias")
//                                                      .alias("connect-kms-"+amazonConnect.getAttrId())
                .build();

        Bucket amazonConnectS3Bucket = Bucket.Builder.create(this, "amazon-connect-s3-bucket")
                .bucketName("amazon-connect-" + connectInstanceAlias.getValueAsString())
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        createS3StorageConfig(amazonConnect, "CALL_RECORDINGS", "connect/" + amazonConnect.getInstanceAlias() + "/CallRecordings", amazonConnectManagedKeyAlias.getKeyArn(), amazonConnectS3Bucket);
        createS3StorageConfig(amazonConnect, "CHAT_TRANSCRIPTS", "connect/" + amazonConnect.getInstanceAlias() + "/ChatTranscripts", amazonConnectManagedKeyAlias.getKeyArn(), amazonConnectS3Bucket);
        createS3StorageConfig(amazonConnect, "SCHEDULED_REPORTS", "connect/" + amazonConnect.getInstanceAlias() + "/Reports", amazonConnectManagedKeyAlias.getKeyArn(), amazonConnectS3Bucket);


        CfnOutput.Builder.create(this, "connect-arn")
                .description("Amazon Connect ARN Name")
                .value(amazonConnect.getAttrArn())
                .build();

        CfnOutput.Builder.create(this, "connect-id")
                .description("Amazon Connect ID")
                .value(amazonConnect.getAttrId())
                .build();

        CfnOutput.Builder.create(this, "connect-instanceName")
                .description("Amazon Connect Instance Name")
                .value(amazonConnect.getInstanceAlias())
                .build();

        CfnOutput.Builder.create(this, "connect-kms")
                .description("Amazon Connect KMS ARN")
                .value(amazonConnectManagedKeyAlias.getKeyArn())
                .build();

        CfnOutput.Builder.create(this, "connect-identity-type")
                .description("Amazon Connect Identity Management Type")
                .value(amazonConnect.getIdentityManagementType())
                .build();

    }

    private void createS3StorageConfig(CfnInstance amazonConnect, String resourceType, String prefix, String encryptionKeyARN, Bucket amazonConnectS3Bucket) {
        CfnInstanceStorageConfig.Builder.create(this, "connect-example-s3-storage-config-" + resourceType)
                .instanceArn(amazonConnect.getAttrArn())
                .s3Config(CfnInstanceStorageConfig.S3ConfigProperty.builder()
                        .bucketName(amazonConnectS3Bucket.getBucketName())
                        .bucketPrefix(prefix)
                        .encryptionConfig(CfnInstanceStorageConfig.EncryptionConfigProperty.builder()
                                .encryptionType("KMS")
                                .keyId(encryptionKeyARN)
                                .build())
                        .build())
                .resourceType(resourceType)
                .storageType("S3")
                .build();
    }
}

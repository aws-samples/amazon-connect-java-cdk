/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 */

package com.amazonaws.cdk;


import io.github.cdklabs.cdknag.NagPackSuppression;
import io.github.cdklabs.cdknag.NagSuppressions;
import software.amazon.awscdk.*;
import software.amazon.awscdk.customresources.*;
import software.amazon.awscdk.services.connect.*;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.constructs.Construct;

import java.util.ArrayList;
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
                .defaultValue("connect-" + System.currentTimeMillis())
                .type("String")
                .build();

        CfnParameter userMgmtType = CfnParameter.Builder.create(this, "userManagementType")
                .description("default is CONNECT_MANAGED, select SAML if you want your connect instance to use centralized User Management")
                .defaultValue("CONNECT_MANAGED")
                .type("String")
                .allowedValues(List.of("SAML", "CONNECT_MANAGED"))
                .build();

        // Logging S3 Bucket's KMS Key
        Key loggingBucketKey = Key.Builder.create(this, "LoggingBucketKey")
                .alias("LoggingBucketKey4Connect")
                .enableKeyRotation(true)
                .pendingWindow(Duration.days(7))
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Logging S3 Bucket
        Bucket loggingBucket = Bucket.Builder.create(this, "LoggingBucket")
                .enforceSsl(true)
                .encryption(BucketEncryption.KMS)
                .encryptionKey(loggingBucketKey)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .versioned(true)
                .removalPolicy(RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .build();


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
                        // arn:aws:connect:{Region}:{Account}:instance/{InstanceId}
                        .resources(List.of("arn:aws:connect:" + getRegion() + ":" + getAccount() + ":instance/" + amazonConnect.getAttrId()))
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
                        // arn:aws:connect:{Region}:{Account}:instance/{InstanceId}
                        .resources(List.of("arn:aws:connect:" + getRegion() + ":" + getAccount() + ":instance/" + amazonConnect.getAttrId()))
                        .build()))
                .build();

        String securityProfileARN = awsCustomResourceListSecurityProfiles.getResponseField("SecurityProfileSummaryList.3.Arn");

        CfnUser amazonTestUser = CfnUser.Builder.create(this, "connect-example-admin")
                .username("testuser@email.com")
                .password("Password@123")
                // Attaching this User with the Amazon Connect Instance using ARN
                .instanceArn(amazonConnect.getAttrArn())
                .identityInfo(CfnUser.UserIdentityInfoProperty.builder()
                        .firstName("Test")
                        .lastName("User")
                        .build())
                .phoneConfig(CfnUser.UserPhoneConfigProperty.builder()
                        .phoneType("SOFT_PHONE")
                        .build())
                .routingProfileArn(routingProfileARN)
                .securityProfileArns(List.of(securityProfileARN))
                .build();

        // KMS Key for Amazon Connect Encryption
        Key amazonConnectManagedKeyAlias = Key.Builder.create(this, "AmazonConnectManagedKeyAlias")
                .alias("AmazonConnectKMSKey")
                .enableKeyRotation(true)
                .pendingWindow(Duration.days(7))
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();


        // Amazon Connect S3 Bucket
        Bucket amazonConnectS3Bucket = Bucket.Builder.create(this, "amazon-connect-s3-bucket")
                .bucketName("amazon-connect-" + connectInstanceAlias.getValueAsString())
                .enforceSsl(true)
                .encryption(BucketEncryption.KMS)
                .encryptionKey(amazonConnectManagedKeyAlias)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .versioned(true)
                .serverAccessLogsBucket(loggingBucket)
                .serverAccessLogsPrefix("connectBucket/")
                // Below 2 options can be ignored for Prod Amazon Connect Instance.
                .removalPolicy(RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .build();

        createS3StorageConfig(amazonConnect, "CALL_RECORDINGS", "connect/" + amazonConnect.getInstanceAlias() + "/CallRecordings", amazonConnectManagedKeyAlias.getKeyArn(), amazonConnectS3Bucket);
        createS3StorageConfig(amazonConnect, "CHAT_TRANSCRIPTS", "connect/" + amazonConnect.getInstanceAlias() + "/ChatTranscripts", amazonConnectManagedKeyAlias.getKeyArn(), amazonConnectS3Bucket);
        createS3StorageConfig(amazonConnect, "SCHEDULED_REPORTS", "connect/" + amazonConnect.getInstanceAlias() + "/Reports", amazonConnectManagedKeyAlias.getKeyArn(), amazonConnectS3Bucket);

        // Claim Phone Number for Amazon Connect Instance
        CfnPhoneNumber cfnPhoneNumber = CfnPhoneNumber.Builder.create(this, "connect-example-phone-number")
                .countryCode("US")
                .targetArn(amazonConnect.getAttrArn())
                .type("TOLL_FREE")
                .build();

        // Create Hours of Operation Config for Escalation Queue from 8am to 5pm
        ArrayList<CfnHoursOfOperation.HoursOfOperationConfigProperty> dayConfigs = new ArrayList<>();
        List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY").forEach(day -> {
            dayConfigs.add(CfnHoursOfOperation.HoursOfOperationConfigProperty.builder()
                    .day(day)
                    .startTime(CfnHoursOfOperation.HoursOfOperationTimeSliceProperty.builder()
                            .hours(8)
                            .minutes(0)
                            .build())
                    .endTime(CfnHoursOfOperation.HoursOfOperationTimeSliceProperty.builder()
                            .hours(17)
                            .minutes(0)
                            .build())
                    .build());
        });

        // Create Hours of Operation
        CfnHoursOfOperation cfnHoursOfOperation = CfnHoursOfOperation.Builder.create(this, "amazon-connect-hours-of-operation")
                .name("Escalation Hours of Operation")
                .description("This Hours of Operation is used for Escalation and open weekdays from 8am to 5pm")
                .instanceArn(amazonConnect.getAttrArn())
                .timeZone("US/Pacific")
                .config(dayConfigs)
                .build();

        // Amazon Connect Create new Queue
        CfnQueue escalationQueue = CfnQueue.Builder.create(this, "amazon-connect-escalation-queue")
                .description("This Queue is used for Escalation")
                .instanceArn(amazonConnect.getAttrArn())
                .name("EscalationQueue")
                .outboundCallerConfig(CfnQueue.OutboundCallerConfigProperty.builder()
                        .outboundCallerIdName("AnyCompanyPrioritySupport")
                        .outboundCallerIdNumberArn(cfnPhoneNumber.getAttrPhoneNumberArn())
                        .build())
                .hoursOfOperationArn(cfnHoursOfOperation.getAttrHoursOfOperationArn())
                .build();

        // Amazon Connect Create new Routing Profile
        CfnRoutingProfile.Builder.create(this, "amazon-connect-routing-profile")
                .description("This Routing Profile is used for Escalation")
                .instanceArn(amazonConnect.getAttrArn())
                .name("EscalationRoutingProfile")
                .defaultOutboundQueueArn(escalationQueue.getAttrQueueArn())
                .queueConfigs(List.of(CfnRoutingProfile.RoutingProfileQueueConfigProperty.builder()
                        .priority(1)
                        .queueReference(CfnRoutingProfile.RoutingProfileQueueReferenceProperty.builder()
                                .queueArn(escalationQueue.getAttrQueueArn())
                                .channel("VOICE")
                                .build())
                        .delay(0)
                        .build()))
                .mediaConcurrencies(List.of(
                        CfnRoutingProfile.MediaConcurrencyProperty.builder()
                                .channel("VOICE")
                                .concurrency(1)
                                .build(),
                        CfnRoutingProfile.MediaConcurrencyProperty.builder()
                                .channel("CHAT")
                                .concurrency(3)
                                .build()))
                .build();


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


        //CDK NAG Suppression's
        NagSuppressions.addResourceSuppressionsByPath(this, "/AmazonConnectStack/AWS679f53fac002430cb0da5b7982bd2287/ServiceRole/Resource",
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

//        NagSuppressions.addStackSuppressions(this, List.of(NagPackSuppression.builder()
//                .id("AwsSolutions-IAM5")
//                .reason("The IAM entity in this example contain wildcard permissions. In a real world production workload it is recommended adhering to AWS security best practices regarding least-privilege permissions (https://docs.aws.amazon.com/IAM/latest/UserGuide/best-practices.html#grant-least-privilege)")
//                .build()));
//
//        NagSuppressions.addStackSuppressions(this, List.of(NagPackSuppression.builder()
//                .id("AwsSolutions-L1")
//                .reason("Java 11 is LTS version")
//                .build()));

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

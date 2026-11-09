package com.booksmith.media.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
class S3Config {

    @Bean
    S3Client s3Client(MediaProperties properties) {
        var builder = S3Client.builder().region(Region.of(properties.region()));

        if (properties.usesCustomEndpoint()) {
            // MinIO local: credenciais estáticas e path-style
            builder.endpointOverride(URI.create(properties.endpoint()))
                    .forcePathStyle(true)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }

        return builder.build();
    }
}

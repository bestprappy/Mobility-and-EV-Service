package com.navio.mobilityandevservice.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GoogleMapsProperties.class)
public class RestClientConfiguration {

    public static final String GOOGLE_API_KEY_HEADER = "X-Goog-Api-Key";

    @Bean
    @Primary
    RestClient.Builder externalRestClientBuilder(RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder());
    }

    @Bean
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder(RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder());
    }

    @Bean
    RestClient googlePlacesRestClient(
            @Qualifier("externalRestClientBuilder") RestClient.Builder builder,
            GoogleMapsProperties properties
    ) {
        return createGoogleClient(builder, properties.apiKey(), properties.places());
    }

    @Bean
    RestClient googleRoutesRestClient(
            @Qualifier("externalRestClientBuilder") RestClient.Builder builder,
            GoogleMapsProperties properties
    ) {
        return createGoogleClient(builder, properties.apiKey(), properties.routes());
    }

    private RestClient createGoogleClient(
            RestClient.Builder builder,
            String apiKey,
            GoogleMapsProperties.Endpoint endpoint
    ) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withTimeouts(endpoint.connectTimeout(), endpoint.readTimeout());

        return builder.clone()
                .baseUrl(endpoint.baseUrl().toString())
                .defaultHeader(GOOGLE_API_KEY_HEADER, apiKey)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
                .build();
    }
}

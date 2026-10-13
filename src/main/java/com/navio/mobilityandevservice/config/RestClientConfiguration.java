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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GoogleMapsProperties.class, OpenTripPlannerProperties.class})
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

    @Bean
    RestClient openTripPlannerRestClient(
            @Qualifier("externalRestClientBuilder") RestClient.Builder builder,
            OpenTripPlannerProperties properties
    ) {
        // OTP's Grizzly server does not correctly handle the JDK client's h2c upgrade body.
        // Use HTTP/1.1 for this local/self-hosted endpoint; Google's HTTPS clients stay unchanged.
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.clone()
                .baseUrl(properties.endpoint().toString())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en")
                .requestFactory(requestFactory)
                .build();
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

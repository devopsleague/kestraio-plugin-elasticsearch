package io.kestra.plugin.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.SneakyThrows;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

@KestraTest
public class ElsContainer {

    private static final ElasticsearchContainer elasticsearchContainer;
    protected static ElasticsearchClient elasticsearchClient;

    static {
        elasticsearchContainer = new ElasticsearchContainer(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.17.2"))
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withExposedPorts(9200)
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                cmd.getHostConfig().withPortBindings(
                    List.of(new PortBinding(
                        Ports.Binding.bindPort(9200),
                        new ExposedPort(9200)
                    ))
                )
            ));

        elasticsearchContainer.start();

        loadGBitIndex();

        RestClient restClient = RestClient.builder(
            HttpHost.create(elasticsearchContainer.getHttpHostAddress())
        ).build();

        elasticsearchClient = new ElasticsearchClient(
            new RestClientTransport(restClient, new co.elastic.clients.json.jackson.JacksonJsonpMapper())
        );
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (elasticsearchContainer != null) {
                elasticsearchContainer.stop();
            }
        }));
    }

    @Inject
    protected RunContextFactory runContextFactory;

    @Value("${elasticsearch-hosts}")
    protected List<String> hosts;

    @SneakyThrows
    public static void loadGBitIndex() {
        URL url = ElsContainer.class.getClassLoader().getResource("indexes/gbit.txt");
        HttpClient client = HttpClient.newHttpClient();
        String bulkContent = Files.readString(Path.of(url.toURI()));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:9200/_bulk"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(bulkContent))
            .build();
        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to execute bulk request: " + response.body());
        }

        client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:9200/gbit/_refresh"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .header("Content-Type", "application/json")
            .build(), HttpResponse.BodyHandlers.ofString());
    }

}

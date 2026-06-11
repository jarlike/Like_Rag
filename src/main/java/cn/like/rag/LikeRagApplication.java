package cn.like.rag;

import cn.like.rag.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RagProperties.class)
public class LikeRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(LikeRagApplication.class, args);
    }
}

package com.github.novicezk.midjourney;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.CrossOrigin;
import spring.config.BeanConfig;
import spring.config.WebMvcConfig;

@EnableScheduling
@SpringBootApplication
@Import({BeanConfig.class, WebMvcConfig.class})
@CrossOrigin
public class ProxyApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProxyApplication.class, args);
	}

}

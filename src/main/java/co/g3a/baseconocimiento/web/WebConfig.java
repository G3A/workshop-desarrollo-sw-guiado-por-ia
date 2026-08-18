package co.g3a.baseconocimiento.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class WebConfig {

    @Bean
    FilterRegistrationBean<RedireccionIndiceFilter> redireccionIndiceFilter() {
        FilterRegistrationBean<RedireccionIndiceFilter> registro =
                new FilterRegistrationBean<>(new RedireccionIndiceFilter());
        registro.addUrlPatterns("/index.html");
        return registro;
    }
}

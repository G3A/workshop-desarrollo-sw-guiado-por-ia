package co.g3a.baseconocimiento.seguridad;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SeguridadConfig {

    @Bean
    FilterRegistrationBean<ApiTokenFilter> apiTokenFilter(SeguridadPropiedades propiedades) {
        FilterRegistrationBean<ApiTokenFilter> registro =
                new FilterRegistrationBean<>(new ApiTokenFilter(propiedades));
        registro.addUrlPatterns("/api/*");
        return registro;
    }
}

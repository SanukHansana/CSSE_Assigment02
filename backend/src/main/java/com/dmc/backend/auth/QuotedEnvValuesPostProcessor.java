package com.dmc.backend.auth;

import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;

/** Accept matching outer quotes in the imported .env file without changing the private file. */
public class QuotedEnvValuesPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 11; }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        for (var source : List.copyOf(environment.getPropertySources().stream().toList())) {
            if (!(source instanceof EnumerablePropertySource<?> properties) || !source.getName().contains(".env")) continue;
            var values = new LinkedHashMap<String, Object>();
            for (String name : properties.getPropertyNames()) {
                Object value = properties.getProperty(name);
                if (value instanceof String text && text.length() >= 2) {
                    char first = text.charAt(0);
                    if ((first == '\'' || first == '"') && text.charAt(text.length() - 1) == first) {
                        value = text.substring(1, text.length() - 1);
                    }
                }
                values.put(name, value);
            }
            environment.getPropertySources().replace(source.getName(), new MapPropertySource(source.getName(), values));
        }
    }
}

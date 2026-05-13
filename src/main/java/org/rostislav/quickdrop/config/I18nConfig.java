package org.rostislav.quickdrop.config;

import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Spring MVC internationalisation (i18n) configuration.
 *
 * <p>Locale is persisted in a cookie named {@code lang} (24-hour TTL). When no
 * cookie is present the default locale is read from
 * {@link ApplicationSettingsService#getDefaultLanguage()} so that the admin can
 * change the site-wide default without restarting. Users can switch locale by
 * appending {@code ?lang=<tag>} to any request URL.
 *
 * <p>Message bundles are loaded from {@code classpath:messages*.properties} with
 * UTF-8 encoding. Validation messages resolve through the same {@link MessageSource}
 * so that constraint violation texts are also translated.
 */
@Configuration
public class I18nConfig implements WebMvcConfigurer {

    private final ApplicationSettingsService applicationSettingsService;

    public I18nConfig(ApplicationSettingsService applicationSettingsService) {
        this.applicationSettingsService = applicationSettingsService;
    }

    /**
     * Registers the reloadable message source backed by {@code messages*.properties} files.
     *
     * @return configured message source
     */
    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasename("classpath:messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setUseCodeAsDefaultMessage(true);
        return messageSource;
    }

    /**
     * Cookie-based locale resolver whose default locale is driven by the application settings.
     *
     * @return configured locale resolver
     */
    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver();
        resolver.setDefaultLocaleFunction(request -> {
            String lang = applicationSettingsService.getDefaultLanguage();
            if (lang != null && !lang.isBlank()) {
                return Locale.forLanguageTag(lang);
            }
            return Locale.ENGLISH;
        });
        resolver.setCookieName("lang");
        resolver.setCookieMaxAge(24 * 60 * 60); // 24 hours
        return resolver;
    }

    /**
     * Interceptor that switches the active locale when a {@code lang} query parameter is present.
     *
     * @return configured interceptor
     */
    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        return interceptor;
    }

    /**
     * Validator factory that uses the application's {@link MessageSource} for constraint messages.
     *
     * @param messageSource the message source to resolve validation messages from
     * @return configured validator factory
     */
    @Bean
    public LocalValidatorFactoryBean validator(MessageSource messageSource) {
        LocalValidatorFactoryBean factoryBean = new LocalValidatorFactoryBean();
        factoryBean.setValidationMessageSource(messageSource);
        return factoryBean;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }

    @Override
    public Validator getValidator() {
        return validator(messageSource());
    }
}

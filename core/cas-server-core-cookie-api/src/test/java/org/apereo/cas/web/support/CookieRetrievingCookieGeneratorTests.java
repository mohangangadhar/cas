package org.apereo.cas.web.support;

import org.apereo.cas.authentication.CoreAuthenticationTestUtils;
import org.apereo.cas.authentication.RememberMeCredential;
import org.apereo.cas.authentication.adaptive.geo.GeoLocationService;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.configuration.model.support.cookie.CookieProperties;
import org.apereo.cas.configuration.model.support.cookie.PinnableCookieProperties;
import org.apereo.cas.test.CasTestExtension;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.util.MockRequestContext;
import org.apereo.cas.util.crypto.CipherExecutor;
import org.apereo.cas.util.spring.DirectObjectProvider;
import org.apereo.cas.web.cookie.CookieGenerationContext;
import org.apereo.cas.web.support.gen.CookieRetrievingCookieGenerator;
import org.apereo.cas.web.support.mgmr.DefaultCasCookieValueManager;
import org.apereo.cas.web.support.mgmr.DefaultCookieSameSitePolicy;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Arrays;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * This is {@link CookieRetrievingCookieGeneratorTests}.
 *
 * @author Misagh Moayyed
 * @since 6.0.0
 */
@Tag("Cookie")
@SpringBootTest(classes = RefreshAutoConfiguration.class)
@EnableConfigurationProperties(CasConfigurationProperties.class)
@ExtendWith(CasTestExtension.class)
class CookieRetrievingCookieGeneratorTests {
    @Autowired
    private ConfigurableApplicationContext applicationContext;

    private static CookieGenerationContext getCookieGenerationContext(final String path) {
        return CookieUtils.buildCookieGenerationContext(new CookieProperties()
            .setName("cas")
            .setPath(path)
            .setMaxAge("1000")
            .setDomain("example.org")
            .setSecure(true)
            .setHttpOnly(true));
    }

    private static CookieGenerationContext getCookieGenerationContext() {
        return getCookieGenerationContext("/");
    }

    @Test
    void verifyCookiePathNotModified() throws Throwable {
        val request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var gen1 = CookieUtils.buildCookieRetrievingGenerator(getCookieGenerationContext("/custom/path/"));
        var cookie1 = gen1.addCookie(request, response, "some-value");
        assertEquals("/custom/path/", cookie1.getPath());

        gen1 = CookieUtils.buildCookieRetrievingGenerator(getCookieGenerationContext(StringUtils.EMPTY));
        cookie1 = gen1.addCookie(request, response, "some-value");
        assertEquals("/", cookie1.getPath());
    }

    @Test
    void verifyRemoveAllCookiesByName() throws Throwable {
        val request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        val gen1 = CookieUtils.buildCookieRetrievingGenerator(getCookieGenerationContext());
        val cookie1 = gen1.addCookie(request, response, "some-value");

        val gen2 = CookieUtils.buildCookieRetrievingGenerator(getCookieGenerationContext("/cas"));
        val cookie2 = gen2.addCookie(request, response, "some-value");

        val gen3 = CookieUtils.buildCookieRetrievingGenerator(getCookieGenerationContext("/cas/"));
        val cookie3 = gen3.addCookie(request, response, "some-value");

        request.setCookies(cookie1, cookie2, cookie3);
        response = new MockHttpServletResponse();
        gen1.removeAll(request, response);
        assertEquals(3, response.getCookies().length);
        assertTrue(Arrays.stream(response.getCookies()).allMatch(cookie -> cookie.getMaxAge() == 0));
    }

    @Test
    void verifyExistingCookieInResponse() throws Throwable {
        val context = getCookieGenerationContext();
        val request = new MockHttpServletRequest();
        val response = new MockHttpServletResponse();
        val gen = CookieUtils.buildCookieRetrievingGenerator(context);

        var cookie = gen.addCookie(request, response, "some-value");
        assertNotNull(cookie);
        var headers = response.getHeaders("Set-Cookie");
        assertEquals(1, headers.size());
        assertTrue(headers.getFirst().contains(context.getName() + '=' + cookie.getValue()));

        cookie = gen.addCookie(request, response, "updated-value");
        assertNotNull(cookie);
        headers = response.getHeaders("Set-Cookie");
        assertEquals(1, headers.size());
        assertTrue(headers.getFirst().contains(context.getName() + '=' + cookie.getValue()));
    }

    @Test
    void verifyOtherSetCookieHeaderIsNotDiscarded() throws Throwable {
        val context = getCookieGenerationContext();

        val gen = CookieUtils.buildCookieRetrievingGenerator(context);
        val request = new MockHttpServletRequest();
        val response = new MockHttpServletResponse();
        response.addHeader("Set-Cookie", gen.getCookieName() + "=some-cookie-value");
        response.addHeader("Set-Cookie", "OtherCookie=other-cookie-value");
        var headers = response.getHeaders("Set-Cookie");
        assertEquals(2, headers.size());
        val cookie = gen.addCookie(request, response, "some-value");
        assertNotNull(cookie);
        assertEquals("some-value", cookie.getValue());
        var headersAfter = response.getHeaders("Set-Cookie");
        assertEquals(2, headersAfter.size());
        val headerValuesAfter = response.getHeaderValues("Set-Cookie").stream()
            .map(String.class::cast)
            .map(header -> Arrays.stream(header.split(";")).iterator().next())
            .collect(Collectors.toSet());
        assertEquals(headerValuesAfter, CollectionUtils.wrapSet(cookie.getName() + "=some-value", "OtherCookie=other-cookie-value"));
    }

    @Test
    void verifyCookieValueMissing() throws Throwable {
        val context = getCookieGenerationContext();
        context.setName(StringUtils.EMPTY);

        val gen = CookieUtils.buildCookieRetrievingGenerator(context);
        val request = new MockHttpServletRequest();
        request.addHeader(context.getName(), "CAS-Cookie-Value");
        val cookie = gen.retrieveCookieValue(request);
        assertNull(cookie);
    }

    @Test
    void verifyCookieSameSiteLax() throws Throwable {
        val ctx = getCookieGenerationContext();
        ctx.setSameSitePolicy("lax");

        val gen = CookieUtils.buildCookieRetrievingGenerator(new DefaultCasCookieValueManager(
            CipherExecutor.noOp(),
            new DirectObjectProvider<>(mock(GeoLocationService.class)),
            DefaultCookieSameSitePolicy.INSTANCE, new PinnableCookieProperties().setPinToSession(false)), ctx);
        val context = MockRequestContext.create();
        gen.addCookie(context.getHttpServletRequest(), context.getHttpServletResponse(), false, "CAS-Cookie-Value");
        val cookie = (MockCookie) context.getHttpServletResponse().getCookie(ctx.getName());
        assertNotNull(cookie);
        assertEquals("Lax", cookie.getSameSite());
    }

    @Test
    void verifyCookieValueByHeader() throws Throwable {
        val context = getCookieGenerationContext();

        val gen = CookieUtils.buildCookieRetrievingGenerator(context);
        val request = new MockHttpServletRequest();
        request.addHeader(context.getName(), "CAS-Cookie-Value");
        val cookie = gen.retrieveCookieValue(request);
        assertNotNull(cookie);
        assertEquals("CAS-Cookie-Value", cookie);
    }

    @Test
    void verifyCookieForRememberMeByAuthnRequest() throws Throwable {
        val ctx = getCookieGenerationContext();

        val gen = CookieUtils.buildCookieRetrievingGenerator(ctx);
        val context = MockRequestContext.create(applicationContext);
        context.setParameter(RememberMeCredential.REQUEST_PARAMETER_REMEMBER_ME, "true");
        WebUtils.putRememberMeAuthenticationEnabled(context, Boolean.TRUE);

        gen.addCookie(context.getHttpServletRequest(), context.getHttpServletResponse(),
            CookieRetrievingCookieGenerator.isRememberMeAuthentication(context), "CAS-Cookie-Value");
        val cookie = context.getHttpServletResponse().getCookie(ctx.getName());
        assertNotNull(cookie);
        assertEquals(ctx.getRememberMeMaxAge(), cookie.getMaxAge());
    }

    @Test
    void verifyCookieForRememberMeByRequestContext() throws Throwable {
        val ctx = getCookieGenerationContext();

        val gen = CookieUtils.buildCookieRetrievingGenerator(ctx);
        val context = MockRequestContext.create(applicationContext);

        val authn = CoreAuthenticationTestUtils.getAuthentication("casuser",
            CollectionUtils.wrap(RememberMeCredential.AUTHENTICATION_ATTRIBUTE_REMEMBER_ME, CollectionUtils.wrap(Boolean.TRUE)));
        WebUtils.putAuthentication(authn, context);
        WebUtils.putRememberMeAuthenticationEnabled(context, Boolean.TRUE);

        gen.addCookie(context.getHttpServletRequest(), context.getHttpServletResponse(),
            CookieRetrievingCookieGenerator.isRememberMeAuthentication(context), "CAS-Cookie-Value");
        val cookie = context.getHttpServletResponse().getCookie(ctx.getName());
        assertNotNull(cookie);
        assertEquals(ctx.getRememberMeMaxAge(), cookie.getMaxAge());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "5000", "PT1H"})
    void verifyCookieMaxAge(final String maxAge) throws Throwable {
        val request = new MockHttpServletRequest();
        val response = new MockHttpServletResponse();
        val context = CookieUtils.buildCookieGenerationContext(new CookieProperties()
            .setName("cas")
            .setPath("/cas")
            .setMaxAge(maxAge)
            .setDomain("example.org")
            .setSecure(true));
        val gen = CookieUtils.buildCookieRetrievingGenerator(context);
        val cookie = gen.addCookie(request, response, "some-value");
        assertEquals(cookie.getMaxAge(), CookieUtils.getCookieMaxAge(maxAge));
    }
}

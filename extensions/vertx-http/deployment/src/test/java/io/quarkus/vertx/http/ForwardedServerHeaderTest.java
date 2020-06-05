package io.quarkus.vertx.http;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.RestAssured;
import org.hamcrest.Matchers;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

public class ForwardedServerHeaderTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(ForwardedHandlerInitializer.class)
                    .addAsResource(new StringAsset("quarkus.http.proxy-address-forwarding=true\n" +
                            "quarkus.http.forwarded_host_header=X_FORWARDED_SERVER"),
                            "application.properties"));

    @Test
    public void testWithHostAndServer() {
        assertThat(RestAssured.get("/forward").asString()).startsWith("http|");

        RestAssured.given()
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-For", "backend:4444")
                .header("X-Forwarded-Host", "somehost")
                .header("X-Forwarded-Server", "server")
                .get("/forward")
                .then()
                .body(Matchers.equalTo("https|server|backend:4444|/forward"));
    }

}

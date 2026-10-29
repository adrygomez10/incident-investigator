package dev.adriangm.investigator.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import dev.adriangm.investigator.MemoryStore;
import dev.adriangm.investigator.adapter.support.Redactor;
import dev.adriangm.investigator.application.ManageInvestigations;
import dev.adriangm.investigator.application.Ports.Issues;
import dev.adriangm.investigator.application.Publisher;
import dev.adriangm.investigator.domain.Investigation.ApprovedIssue;
import dev.adriangm.investigator.domain.ProjectScope;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiContractTest {
    static Issues noWrite()
    {
        return new Issues() {
            @Override
            public String create(ApprovedIssue item)
            {
                throw new AssertionError("No write expected");
            }

            @Override
            public Optional<String> reconcile(ApprovedIssue item)
            {
                return Optional.empty();
            }
        };
    }

    @Test
    void optionalWindowHasStableOriginalRequestFingerprint()
    {
        var store = new MemoryStore();
        var controller = new InvestigationController(
            new ManageInvestigations(
                store,
                new Publisher(noWrite(), store),
                new ProjectScope("orders-demo", "example/orders-demo"),
                new Redactor(List.of()),
                Clock.systemUTC()
            )
        );
        var input = new ManageInvestigations.Start("orders-demo", "Investigate orders", null, null);
        var item = controller.create("same-key", input).getBody();
        String fingerprint = item.requestFingerprint();
        assertEquals(fingerprint, controller.create("same-key", input).getBody().requestFingerprint());
    }

    @Test
    void unknownProjectAndExcessiveWindowAreRejected()
    {
        var store = new MemoryStore();
        var controller = new InvestigationController(
            new ManageInvestigations(
                store,
                new Publisher(noWrite(), store),
                new ProjectScope("orders-demo", "example/orders-demo"),
                new Redactor(List.of()),
                Clock.systemUTC()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> controller.create("key", new ManageInvestigations.Start("other", "Investigate", null, null))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> controller
                .create(
                    "key",
                    new ManageInvestigations.Start(
                        "orders-demo",
                        "Investigate",
                        "2026-01-01T00:00:00Z",
                        "2026-04-01T00:00:01Z"
                    )
                )
        );
    }

    @Test
    void authenticationAndChunkedBodyAreBounded() throws Exception
    {
        String token = "test-operator-token-12345678901234567890";
        var filter = new OperatorFilter(new MockEnvironment().withProperty("OPERATOR_TOKEN", token));
        var wrong = new MockHttpServletRequest("GET", "/investigations");
        var response = new MockHttpServletResponse();
        filter.doFilter(wrong, response, (req, res) -> fail("Unauthenticated request reached controller"));
        assertEquals(401, response.getStatus());
        var large = new MockHttpServletRequest("POST", "/investigations") {
            @Override
            public long getContentLengthLong()
            {
                return -1;
            }
        };
        large.addHeader("Authorization", "Bearer " + token);
        large.setContent(new byte[65537]);
        response = new MockHttpServletResponse();
        filter.doFilter(large, response, (req, res) -> fail("Oversized request reached controller"));
        assertEquals(413, response.getStatus());
    }
}

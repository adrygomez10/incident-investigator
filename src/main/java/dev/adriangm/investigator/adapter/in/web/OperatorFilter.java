package dev.adriangm.investigator.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates the operator and bounds request bodies before they reach the API. */
@Component
public final class OperatorFilter extends OncePerRequestFilter {
  private final byte[] expected;

  /** Requires a configured operator token of at least thirty-two characters. */
  public OperatorFilter(Environment env) {
    String token = env.getProperty("OPERATOR_TOKEN", "");
    if (token.length() < 32) {
      throw new IllegalStateException("OPERATOR_TOKEN must contain at least 32 characters");
    }
    expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getRequestURI().equals("/actuator/health")) {
      chain.doFilter(request, response);
      return;
    }
    byte[] supplied =
        request.getHeader("Authorization") == null
            ? new byte[0]
            : request.getHeader("Authorization").getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, supplied)) {
      response.setStatus(401);
      return;
    }
    if (request.getContentLengthLong() > 65536) {
      response.setStatus(413);
      return;
    }
    if (!request.getMethod().equals("GET")) {
      byte[] data = request.getInputStream().readNBytes(65537);
      if (data.length > 65536) {
        response.setStatus(413);
        return;
      }
      var input = new java.io.ByteArrayInputStream(data);
      request =
          new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
              return new ServletInputStream() {
                @Override
                public int read() {
                  return input.read();
                }

                @Override
                public boolean isFinished() {
                  return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                  return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                  throw new UnsupportedOperationException("Async request body is not supported");
                }
              };
            }
          };
    }
    chain.doFilter(request, response);
  }
}
